# Tiered Billing Service · 阶梯折扣计费服务

> 一个聚焦 **并发一致性、幂等设计与计费规则实现** 的 Spring Boot 后端服务。  
> 系统按照当月累计消费额执行阶梯折扣，支持 CNY / USD 多币种换算，并通过 **唯一索引 + 悲观锁 + 事务边界控制** 保证并发扣费场景下不超扣、不丢更新，同时实现重复请求的响应幂等。

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-green.svg)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-Database-orange.svg)](https://www.mysql.com/)
[![Tests](https://img.shields.io/badge/Tests-35%20passed-brightgreen.svg)](#11-自动化测试与并发测试)

---

## 目录

- [1. 项目背景](#1-项目背景)
- [2. 核心业务规则](#2-核心业务规则)
- [3. 技术栈](#3-技术栈)
- [4. 系统架构](#4-系统架构)
- [5. 项目结构](#5-项目结构)
- [6. 数据库设计](#6-数据库设计)
- [7. API 说明](#7-api-说明)
- [8. 本地启动](#8-本地启动)
- [9. MySQL 初始化](#9-mysql-初始化)
- [10. 调用示例](#10-调用示例)
- [11. 自动化测试与并发测试](#11-自动化测试与并发测试)
- [12. 核心技术亮点](#12-核心技术亮点)
- [13. 工程优化案例](#13-工程优化案例)
- [14. AI-Assisted Development](#14-ai-assisted-development)

---

## 1. 项目背景

在真实计费、支付或账户系统中，扣费操作通常需要同时解决两个核心问题：

1. **并发一致性**：同一账户同时收到多笔扣费请求时，余额不能被超扣，累计消费金额不能出现丢失更新。
2. **幂等性**：网络超时、自动重试或用户重复点击可能导致同一笔业务被重复提交，系统必须保证同一笔交易只执行一次，并让重复请求得到一致结果。

本项目以“阶梯折扣计费”为业务场景，实现一个完整、可运行、经过单元测试、集成测试与真实多线程并发测试验证的后端服务。

核心目标是：

> 在并发和重复请求场景下，保证账户余额、累计消费额和交易流水始终保持一致。

---

## 2. 核心业务规则

### 2.1 阶梯折扣

系统根据用户当前月份已有累计消费额 `monthlyTotal`，判断本次交易适用的折扣档位。

| 当月累计消费额 | 折扣档位 | 折扣系数 |
|---|---|---|
| `< 100` 元 | 原价 | `1.00` |
| `100 ≤ x < 500` 元 | 8 折 | `0.80` |
| `≥ 500` 元 | 5 折 | `0.50` |

规则说明：

- 折扣档位按照本笔交易发生前的 `monthlyTotal` 判断；
- 实际扣费金额 = 折算为 CNY 后的金额 × 折扣系数；
- 金额统一保留两位小数并采用四舍五入。

### 2.2 多币种换算

| 币种 | CNY 汇率 | 说明 |
|---|---:|---|
| CNY | `1.0` | 基准币种 |
| USD | `7.2` | 固定汇率 |
| JPY | — | 当前不支持，返回 `400` |

### 2.3 requestId 幂等语义

`requestId` 代表一笔业务交易的唯一标识，而不是每次 HTTP 请求的编号。

因此：

- 网络超时重试时复用同一个 `requestId`；
- 客户端自动重试时复用同一个 `requestId`；
- 用户重复提交同一笔业务时复用同一个 `requestId`；
- 只有新交易才生成新的 `requestId`。

前端防重复提交只能改善用户体验，真正的幂等保障必须由后端完成。

---

## 3. 技术栈

| 层面 | 技术 | 说明 |
|---|---|---|
| Language | Java 17 | `pom.xml` 明确指定 |
| Framework | Spring Boot 4.1.0 | Spring MVC + Spring Data JPA |
| Persistence | JPA / Hibernate | 通过 Spring Data JPA 实现实体映射与悲观锁 |
| Database | MySQL | 生产数据库 |
| Test Database | H2 | 测试环境隔离 |
| Validation | Jakarta Validation | 请求参数校验 |
| Testing | Spring Boot Test / MockMvc / JUnit | 单元、集成与并发测试 |
| Build | Maven / Maven Wrapper | 项目构建与依赖管理 |

其中 Java 17 与 Spring Boot 4.1.0 均由 `pom.xml` 明确指定；MySQL Connector、H2、JPA/Hibernate 与测试依赖的具体小版本由 Spring Boot 的依赖管理统一解析，因此 README 不额外写死这些版本号。

---

## 4. 系统架构

### 4.1 分层架构

```text
Controller
   │
   ▼
Service
   │
   ▼
Repository
   │
   ▼
MySQL
```

各层职责如下：

- `Controller`：接收请求、参数校验、返回响应；
- `Service`：完成折扣计算、币种换算、扣费、幂等恢复等核心业务；
- `Repository`：负责数据库访问、悲观锁查询和幂等查询；
- `MySQL`：完成账户、交易流水持久化及唯一索引、行锁兜底。

同时将部分规则拆分为独立业务组件：

- `DiscountService`：负责阶梯折扣判断与金额计算；
- `CurrencyService`：负责币种换算和不支持币种校验。

### 4.2 并发与幂等保障

系统使用三层机制协同保证一致性：

```text
并发请求
   │
   ├── requestId 唯一索引
   │      ↓
   │   防止同一业务重复落库
   │
   ├── 悲观锁 SELECT ... FOR UPDATE
   │      ↓
   │   同一账户写操作串行化
   │
   └── 事务
          ↓
      扣款、累计、流水原子提交
```

三种机制的职责不同：

| 机制 | 主要作用 |
|---|---|
| 唯一索引 | 防止同一个 `requestId` 重复写入 |
| 悲观锁 | 防止同一账户并发读改写导致余额覆盖 |
| 事务 | 保证账户更新和流水写入整体提交或整体回滚 |

---

## 5. 项目结构

```text
tiered-billing-service/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
├── .gitignore
├── .gitattributes
├── README.md
├── sql/
│   └── schema.sql
└── src/
    ├── main/
    │   ├── java/com/billing/billing_service/
    │   │   ├── controller/
    │   │   ├── dto/
    │   │   ├── entity/
    │   │   ├── enums/
    │   │   ├── exception/
    │   │   ├── repository/
    │   │   └── service/
    │   └── resources/
    │       └── application.properties
    └── test/
        ├── java/
        └── resources/
```

---

## 6. 数据库设计

系统包含两张核心表。

### 6.1 account

账户表保存余额、当月累计消费额和统计月份。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 主键 |
| `user_id` | BIGINT UNIQUE | 用户业务 ID |
| `balance` | DECIMAL(15,2) | 当前余额 |
| `monthly_total` | DECIMAL(15,2) | 当月累计消费金额 |
| `month` | VARCHAR(7) | 当前统计月份 |

### 6.2 billing_transaction

交易流水表同时承担幂等记录功能。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 主键 |
| `request_id` | VARCHAR(64) UNIQUE | 幂等键 |
| `user_id` | BIGINT | 用户 ID |
| `original_amount` | DECIMAL(15,2) | 原始金额 |
| `converted_amount` | DECIMAL(15,2) | 折算 CNY 金额 |
| `discount_rate` | DECIMAL(4,2) | 折扣系数 |
| `final_deduct_amount` | DECIMAL(15,2) | 实际扣费金额 |
| `balance_after` | DECIMAL(15,2) | 扣费后余额 |
| `status` | VARCHAR(32) | 交易状态 |

金额计算统一使用 `BigDecimal`，数据库金额字段统一使用 `DECIMAL`。

---

## 7. API 说明

### 7.1 扣费接口

```http
POST /api/v1/billing/deduct
```

请求示例：

```json
{
  "userId": 1001,
  "originalAmount": 100,
  "currency": "CNY",
  "requestId": "biz-order-20260821-0001"
}
```

成功响应：

```json
{
  "status": "SUCCESS",
  "finalDeductAmount": 100.00,
  "currentBalance": 900.00,
  "message": "扣费成功"
}
```

### 7.2 余额查询

```http
GET /api/v1/billing/balance/{userId}
```

响应示例：

```json
{
  "userId": 1001,
  "balance": 900.00,
  "monthlyTotal": 100.00
}
```

错误响应统一使用：

```json
{
  "code": 400,
  "message": "..."
}
```

---

## 8. 本地启动

### 前置条件

- JDK 17+
- Maven 3.6+
- MySQL 8.x

### 8.1 初始化数据库

```bash
mysql -u root -p < sql/schema.sql
```

### 8.2 配置数据库密码

项目不会在 Git 仓库中保存真实数据库密码。

`application.properties` 使用环境变量：

```properties
spring.datasource.password=${DB_PASSWORD}
```

Windows CMD 临时设置：

```bat
set DB_PASSWORD=your_mysql_password
```

PowerShell：

```powershell
$env:DB_PASSWORD="your_mysql_password"
```

Linux / macOS：

```bash
export DB_PASSWORD=your_mysql_password
```

### 8.3 启动服务

```bash
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8080
```

### 8.4 运行测试

```bash
mvn test
```

测试环境使用 H2 内存数据库，不依赖本机 MySQL。

---

## 9. MySQL 初始化

执行：

```bash
mysql -u root -p < sql/schema.sql
```

脚本将：

1. 创建数据库 `billing_db`；
2. 创建 `account` 和 `billing_transaction` 两张表；
3. 创建 `request_id` 唯一索引；
4. 写入用于验证不同折扣档位的测试账户。

测试数据：

| userId | balance | monthly_total | 折扣档位 |
|---|---:|---:|---|
| 1001 | 1000.00 | 0.00 | 原价 |
| 1002 | 1000.00 | 150.00 | 8 折 |
| 1003 | 1000.00 | 600.00 | 5 折 |

---

## 10. 调用示例

### CNY 原价扣费

```bash
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"originalAmount":100,"currency":"CNY","requestId":"biz-001"}'
```

### USD 8 折扣费

```bash
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1002,"originalAmount":10,"currency":"USD","requestId":"biz-002"}'
```

### 幂等请求

重复提交相同 `requestId`：

```bash
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"originalAmount":100,"currency":"CNY","requestId":"biz-001"}'
```

系统不会再次扣款，而是返回与首次请求一致的业务结果。

---

## 11. 自动化测试与并发测试

项目共包含 **35 个自动化测试，全部通过**。

| 测试类 | 数量 | 主要覆盖 |
|---|---:|---|
| `DiscountServiceTest` | 11 | 阶梯折扣边界、非法金额 |
| `CurrencyServiceTest` | 6 | CNY / USD 换算、JPY 拒绝 |
| `BillingServiceIntegrationTest` | 6 | 扣费、余额不足、幂等、折扣边界 |
| `BillingApiIntegrationTest` | 7 | API、参数校验、异常响应 |
| `BillingConcurrencyTest` | 4 | 多线程并发一致性 |
| `BillingServiceApplicationTests` | 1 | Spring 上下文加载 |
| **Total** | **35** | **全部通过** |

### 并发场景验证

使用 `ExecutorService` 与 `CountDownLatch` 构造真实线程竞争。

| 场景 | 并发数 | 验证结果 |
|---|---:|---|
| 同账户不同 requestId 并发扣款 | 10 | 无丢更新 |
| 相同 requestId 并发提交 | 10 | 只生成 1 条流水，10 个调用结果一致 |
| 同账户连续并发更新 | 20 | balance / monthlyTotal 正确 |
| 临界余额并发扣费 | 30 | 余额不会小于 0 |

其中，相同 `requestId` 的 10 线程并发测试最终结果为：

```text
并发线程数 = 10
成功次数 = 10
失败次数 = 0
最终余额 = 900.00
流水条数 = 1
```

---

## 12. 核心技术亮点

### 12.1 BigDecimal 金额精度

金额计算不使用 `float` 或 `double`。

项目统一使用：

```java
BigDecimal
```

并采用：

```java
setScale(2, RoundingMode.HALF_UP)
```

金额比较使用 `compareTo`，避免 `BigDecimal.equals()` 对 scale 敏感带来的问题。

### 12.2 事务保证原子性

一次扣费包含：

```text
查询账户
→ 判断折扣
→ 扣减余额
→ 更新 monthlyTotal
→ 写入交易流水
```

这些操作必须作为一个整体完成。

因此系统使用事务保证：

> 要么全部成功提交，要么全部回滚。

### 12.3 悲观锁防止 Lost Update

同一账户发生并发扣费时，如果多个线程同时读取旧余额，会产生后写覆盖前写的问题。

项目通过：

```java
@Lock(PESSIMISTIC_WRITE)
```

对应数据库：

```sql
SELECT ... FOR UPDATE
```

让同一账户的写操作串行化，从而避免：

- 丢失更新；
- 余额超扣；
- monthlyTotal 更新错误。

### 12.4 requestId 唯一索引

数据库为：

```text
billing_transaction.request_id
```

建立唯一索引：

```text
uk_tx_request_id
```

即使多个线程同时通过应用层的“先查后写”，数据库仍能保证同一个 `requestId` 最终只能写入一条记录。

### 12.5 数据幂等与响应幂等

数据幂等只保证：

> 同一笔业务不会产生重复数据。

响应幂等进一步要求：

> 重复请求不仅不能重复扣款，还必须返回与首次请求一致的业务结果。

本项目最终同时实现这两层幂等。

---

## 13. 工程优化案例

本项目中最重要的一次优化，是从“数据幂等”升级为“响应幂等”。

### 13.1 初版设计

初版使用：

```text
findByRequestId
→ 悲观锁查询账户
→ 扣款
→ 写流水
```

并使用数据库唯一索引防止重复流水。

在顺序调用时该方案运行正常。

### 13.2 并发测试发现问题

当 10 个线程同时使用相同 `requestId` 发起请求时：

```text
1 个线程成功
9 个线程触发唯一索引冲突
```

最终数据虽然正确，只生成 1 条流水并只扣款 1 次，但其他调用方收到了数据库异常。

这意味着初版只实现了：

```text
数据幂等
```

还没有真正实现：

```text
响应幂等
```

### 13.3 根因

唯一索引异常发生在原事务内部。

事务一旦因为数据库异常被标记为 `rollback-only`，就无法继续在同一个事务中可靠地查询已提交结果并返回。

问题的核心不是唯一索引本身，而是：

> 幂等恢复逻辑与事务核心逻辑绑定在了同一个事务边界中。

### 13.4 优化方案

最终将逻辑拆分为：

```text
deduct(...)
   │
   ├── 快速查询 requestId
   │
   ├── TransactionTemplate
   │      └── doDeduct(...)
   │             ├── 悲观锁
   │             ├── 扣款
   │             └── 写流水
   │
   └── 捕获唯一索引异常
          ↓
      查询已经成功提交的交易
          ↓
      返回相同业务结果
```

通过 `TransactionTemplate` 显式控制事务边界：

1. 核心扣费逻辑在独立事务中执行；
2. 唯一索引冲突时该事务完整回滚；
3. 外层捕获异常；
4. 再次查询已经成功提交的流水；
5. 向重复请求返回与首次请求一致的业务响应。

最终 10 个相同 `requestId` 的并发请求达到：

```text
10 个请求全部正常返回
0 个数据库异常暴露给调用方
只生成 1 条流水
账户只扣款 1 次
```

---

## 14. AI-Assisted Development

This project was developed with an AI-assisted coding workflow using **Claude Code**.

AI tools were used to support:

- requirement decomposition;
- implementation assistance;
- debugging and code review;
- unit and integration test generation;
- concurrency test design;
- iterative verification and refactoring.

The project was continuously validated through manual API testing, automated tests and multi-thread concurrency testing rather than relying solely on generated code.

---

## License

This repository is currently intended for portfolio and learning purposes.
