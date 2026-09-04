# 阶梯折扣计费服务 · Billing Service

> 一个聚焦 **并发一致性** 与 **幂等设计** 的后端计费服务。
> 按「当月累计消费额」阶梯打折，支持多币种换算，用「唯一索引 + 悲观锁 + 事务」三层机制保证扣费在并发场景下既**不超扣、不丢更新**，又**重复请求结果一致**。

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-green.svg)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.x-orange.svg)](https://www.mysql.com/)
[![Build](https://img.shields.io/badge/Tests-35%20passed-brightgreen.svg)](./FINAL_ACCEPTANCE_REPORT.md)

---

## 目录

- [1. 项目背景与目标](#1-项目背景与目标)
- [2. 核心业务规则](#2-核心业务规则)
- [3. 技术栈](#3-技术栈)
- [4. 系统架构](#4-系统架构)
- [5. 项目目录结构](#5-项目目录结构)
- [6. 数据库设计](#6-数据库设计)
- [7. API 说明](#7-api-说明)
- [8. 本地启动与数据库初始化](#8-本地启动与数据库初始化)
- [9. curl 调用示例](#9-curl-调用示例)
- [10. 自动化测试与并发测试结果](#10-自动化测试与并发测试结果)
- [11. 关键实现](#11-关键实现)
- [12. 工程优化案例：从「数据幂等」到「响应幂等」](#12-工程优化案例从数据幂等到响应幂等)

---

## 1. 项目背景与目标

在真实的计费 / 支付系统中，扣费操作必须同时面对两类挑战：

1. **并发一致性** —— 同一账户同时收到多笔扣费请求时，余额必须原子扣减、永不为负，累计消费统计不能「丢更新」。
2. **幂等性** —— 网络超时、客户端自动重试、用户重复点击，会让「同一笔业务」被重复提交。系统必须保证同一笔业务**只扣一次钱**，且**重复调用拿到相同的响应**。

本项目以「阶梯折扣计费」为具体业务场景，实现一个**可运行并包含多线程测试**的后端服务，用于练习和验证**事务、锁、唯一索引、幂等**这些机制在计费场景中的实际使用。

**设计目标**：在并发与重复提交的干扰下，扣费数据始终正确、行为始终可预期。

---

## 2. 核心业务规则

### 2.1 阶梯折扣

按「当月已累计消费额 `monthlyTotal`」判断本笔订单所处档位：

| 当月累计消费额 | 档位 | 折扣系数 |
|---|---|---|
| `< 100` 元 | 原价 | `1.00` |
| `100 ≤ x < 500` 元 | 8 折 | `0.80` |
| `≥ 500` 元 | 5 折 | `0.50` |

- 判定用「本笔**之前**」的累计额；实际扣费 = 折算 CNY 后的金额 × 折扣系数，保留两位小数（四舍五入）。

### 2.2 多币种换算

| 币种 | 汇率（→ CNY） | 说明 |
|---|---|---|
| CNY | `1.0` | 基准币种 |
| USD | `7.2` | 固定汇率 |
| JPY | — | **不支持**，返回 `400` 及明确错误 |

### 2.3 幂等语义

- `requestId` 代表**「一笔业务交易」的唯一标识**，而**不是**每次 HTTP 请求的编号。
- **同一笔业务**在以下情况会**复用同一个 `requestId`**：网络超时重试、客户端自动重试、用户重复点击提交。
- **只有发起一笔全新的交易**时，才生成一个全新的 `requestId`。
- 前端「防重复提交」按钮只是**辅助体验**，**后端幂等才是数据库约束**——因为重试可能来自网关、消息队列、脚本，绕过前端。

---

## 3. 技术栈

| 层面 | 技术 | 说明 |
|---|---|---|
| 语言 | Java 17 | |
| 框架 | Spring Boot 4.1.0 | Spring MVC + Spring Data JPA |
| ORM | Hibernate 7.x | 实体映射、悲观锁 |
| 数据库 | MySQL 8.x（生产）/ H2（测试） | 生产走真实 MySQL，测试用内存库隔离 |
| 参数校验 | Jakarta Validation | `@NotNull` / `@DecimalMin` / `@NotBlank` |
| 测试 | JUnit 5 + SpringBootTest + MockMvc + 真实多线程 | |
| 构建 | Maven | |

> **Spring Boot 4.1 兼容说明**：本项目使用 Jackson 3（`tools.jackson.*`）、新版 `@AutoConfigureMockMvc` 路径、`asString()` 替代废弃的 `asText()`，已全部适配。

---

## 4. 系统架构

### 4.1 系统架构

```text
                         Client
                           │
                           ▼
                 ┌──────────────────┐
                 │ DeductController │
                 │ BalanceController│
                 └────────┬─────────┘
                          │
                          ▼
                  ┌───────────────┐
                  │ BillingService│
                  └───────┬───────┘
                          │
          ┌───────────────┼────────────────┐
          │               │                │
          ▼               ▼                ▼
┌────────────────┐ ┌────────────────┐ ┌────────────────────┐
│DiscountService │ │CurrencyService │ │ TransactionTemplate│
│阶梯折扣计算     │ │币种换算        │ │ 核心扣费事务        │
└────────────────┘ └────────────────┘ └─────────┬──────────┘
                                                │
                                                ▼
                                   ┌─────────────────────────┐
                                   │ Repository Layer        │
                                   │ AccountRepository       │
                                   │ BillingTransactionRepo. │
                                   └───────────┬─────────────┘
                                               │
                                               ▼
                                      ┌─────────────────┐
                                      │      MySQL      │
                                      │ account         │
                                      │ billing_tx      │
                                      │ UNIQUE(request) │
                                      │ row lock        │
                                      └─────────────────┘
```

各层职责：

- `Controller`：接收请求、参数校验、返回响应；
- `BillingService`：组织扣费流程，并处理重复请求；
- `DiscountService`：根据 `monthlyTotal` 判断折扣档位；
- `CurrencyService`：将支持币种换算为 CNY；
- `Repository`：负责账户查询、悲观锁和交易记录读写；
- `MySQL`：保存账户与交易流水，并通过唯一索引和行锁约束并发写入。

### 4.2 三层并发 / 幂等保障

```
并发请求
   │
   ├─【层1】requestId 唯一索引 uk_tx_request_id ── 数据幂等约束（同一笔业务最多一条流水）
   │
   ├─【层2】悲观锁 SELECT ... FOR UPDATE ── 同账户写操作串行化（防丢更新、防超扣）
   │
   └─【层3】事务 ── 「查账户→扣款→累计→写流水」原子提交或整体回滚
```

各机制负责的范围如下：

| 机制 | 解决什么 | 管不住什么 |
|---|---|---|
| 唯一索引 | 同一 `requestId` 重复落库 | 余额 / 累计被并发覆盖 |
| 悲观锁 | 同一账户并发读-改-写互相覆盖 | 同 `requestId` 跨账户重复 |
| 事务 | 多步写操作的原子性 | 单独使用无法覆盖并发竞争窗口 |

---

## 5. 项目目录结构

```
AI_Project_Billing_Service/
├── README.md                      ← 本文件（作品集展示入口）
├── PROJECT_STATUS.md              ← 项目进度与设计快照（接续开发用）
├── FINAL_ACCEPTANCE_REPORT.md     ← 阶段 7 最终验收报告
└── billing-service/               ← Spring Boot 工程
    ├── pom.xml
    ├── sql/schema.sql             ← MySQL 建表脚本 + 种子数据
    └── src/
        ├── main/java/com/billing/billing_service/
        │   ├── BillingServiceApplication.java
        │   ├── controller/         ← DeductController / BalanceController
        │   ├── dto/                ← DeductRequest / DeductResponse / BalanceResponse
        │   ├── entity/             ← Account / BillingTransaction
        │   ├── enums/              ← Currency / DiscountTier / TransactionStatus
        │   ├── exception/          ← BizException / ApiError / GlobalExceptionHandler
        │   ├── repository/         ← AccountRepository（含悲观锁）/ BillingTransactionRepository
        │   └── service/            ← BillingService / DiscountService / CurrencyService
        ├── main/resources/
        │   └── application.properties      ← 生产配置（MySQL）
        └── test/
            ├── java/...            ← 6 个测试类（含真实多线程并发测试）
            └── resources/          ← application-test.properties（H2）+ schema-test.sql
```

---

## 6. 数据库设计

两张表（`InnoDB` + `utf8mb4`）：

### 6.1 `account` —— 账户表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 自增主键 |
| `user_id` | BIGINT UNIQUE | 业务用户 ID |
| `balance` | DECIMAL(15,2) | 当前可用余额（CNY） |
| `monthly_total` | DECIMAL(15,2) | 本月已累计消费额（CNY，折扣判定依据） |
| `month` | VARCHAR(7) | 统计月份 `YYYY-MM`，跨月清零 `monthly_total` |

### 6.2 `billing_transaction` —— 扣费流水表（兼幂等记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 自增主键 |
| `request_id` | VARCHAR(64) **UNIQUE** | **幂等键**，`uk_tx_request_id` |
| `user_id` | BIGINT（索引） | 关联账户 |
| `original_amount` / `converted_amount` | DECIMAL(15,2) | 原始金额 / 折算 CNY 金额 |
| `discount_rate` | DECIMAL(4,2) | 折扣系数 |
| `final_deduct_amount` | DECIMAL(15,2) | 实际扣费金额（CNY） |
| `balance_after` | DECIMAL(15,2) | 扣款后余额 |
| `status` | VARCHAR(32) | `SUCCESS` / `INSUFFICIENT_FUNDS` |

> **金额口径**：`monthly_total` 累计的是「**折扣前、折算 CNY 后的原始金额**」，仅在扣费成功时累加；`balance` 扣减的是「折扣后的实扣金额」。所有金额用 `BigDecimal` + `compareTo`，字段 `DECIMAL(15,2)`、`setScale(2, HALF_UP)`。

---

## 7. API 说明

### 7.1 `POST /api/v1/billing/deduct` —— 扣费

**请求体**：

```json
{
  "userId": 1001,
  "originalAmount": 100,
  "currency": "CNY",
  "requestId": "biz-order-20260821-0001"
}
```

| 字段 | 校验 | 说明 |
|---|---|---|
| `userId` | `@NotNull` | 用户 ID |
| `originalAmount` | `@NotNull @DecimalMin(0.01)` | 原始金额，须 > 0 |
| `currency` | `@NotNull` | `CNY` / `USD` / `JPY` |
| `requestId` | `@NotBlank` | 幂等键 |

**响应体**（`200`）：

```json
{
  "status": "SUCCESS",
  "finalDeductAmount": 100.00,
  "currentBalance": 900.00,
  "message": "扣费成功"
}
```

错误响应统一结构 `{ "code": 400, "message": "..." }`。

### 7.2 `GET /api/v1/billing/balance/{userId}` —— 余额查询

**响应体**（`200`）：

```json
{
  "userId": 1001,
  "balance": 900.00,
  "monthlyTotal": 100.00
}
```

---

## 8. 本地启动与数据库初始化

### 8.1 前置条件

- JDK 17+
- Maven 3.6+
- MySQL 8.x（仅运行服务需要；测试使用 H2 内存数据库）

### 8.2 初始化 MySQL

```bash
cd billing-service
mysql -u root -p < sql/schema.sql
```

`schema.sql` 会：

1. 创建数据库 `billing_db`；
2. 创建 `account`、`billing_transaction` 两张表及 `request_id` 唯一索引；
3. 写入 3 个种子账户，覆盖原价、8 折和 5 折三个档位。

| userId | balance | monthly_total | 档位 |
|---|---:|---:|---|
| 1001 | 1000.00 | 0.00 | 原价 |
| 1002 | 1000.00 | 150.00 | 8 折 |
| 1003 | 1000.00 | 600.00 | 5 折 |

### 8.3 数据库连接配置

配置文件：

```text
billing-service/src/main/resources/application.properties
```

数据库密码通过环境变量读取：

```properties
spring.datasource.password=${DB_PASSWORD}
```

### 8.4 启动服务

```bash
cd billing-service
mvn spring-boot:run
```

默认端口为 `8080`。

### 8.5 运行测试

```bash
cd billing-service
mvn test
```

> 测试自动激活 `test` profile，使用 H2 内存数据库 + `create-drop`，不依赖本机 MySQL，也不会修改本地 MySQL 数据。

---

## 9. curl 调用示例

```bash
# ① CNY 原价扣款（userId 1001，累计 0 → 原价档）：扣 100，余额 900
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"originalAmount":100,"currency":"CNY","requestId":"biz-001"}'

# ② USD 8 折扣款（userId 1002，累计 150 → 8 折）：10 USD × 7.2 × 0.8 = 57.60
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1002,"originalAmount":10,"currency":"USD","requestId":"biz-002"}'

# ③ CNY 5 折扣款（userId 1003，累计 600 → 5 折）：扣 100 → 实扣 50
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1003,"originalAmount":100,"currency":"CNY","requestId":"biz-003"}'

# ④ 幂等：重复提交同一 requestId（返回与首次一致，且不再扣款）
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"originalAmount":100,"currency":"CNY","requestId":"biz-001"}'

# ⑤ JPY 非法币种 → 400
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"originalAmount":1000,"currency":"JPY","requestId":"biz-004"}'

# ⑥ 余额不足 → INSUFFICIENT_FUNDS
curl -X POST http://localhost:8080/api/v1/billing/deduct \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"originalAmount":99999,"currency":"CNY","requestId":"biz-005"}'

# ⑦ 查询余额与本月累计消费
curl http://localhost:8080/api/v1/billing/balance/1001
```

---

## 10. 自动化测试与并发测试结果

### 10.1 测试总览（35 个，全绿）

| 测试类 | 数量 | 覆盖范围 |
|---|---|---|
| `DiscountServiceTest` | 11 | 阶梯折扣边界、非法金额 |
| `CurrencyServiceTest` | 6 | CNY/USD 换算、JPY 拒绝、精度 |
| `BillingServiceIntegrationTest` | 6 | 扣款、余额不足、幂等、100/500 边界 |
| `BillingApiIntegrationTest` | 7 | 接口、参数校验、错误响应 |
| `BillingConcurrencyTest` | 4 | **真实多线程并发** |
| `BillingServiceApplicationTests` | 1 | 应用上下文加载 |
| **合计** | **35** | **全部通过** |

### 10.2 并发测试

用 `ExecutorService` + 双 `CountDownLatch` 让线程**同时**发起，4 个用例：

| 用例 | 场景 | 线程数 | 关键断言 | 结果 |
|---|---|---|---|---|
| 同账户并发扣款 | 不同 `requestId` | 10 | 余额 950、monthlyTotal 600、10 条流水 | ✅ |
| **相同 requestId 并发** | **响应幂等** | 10 | **只 1 条流水、10 调用返回一致、0 异常** | ✅ |
| 防丢更新 | 同账户 20 并发 | 20 | 余额 900、monthlyTotal 700（无 lost update） | ✅ |
| 余额临界 | 余额仅 100，30 并发扣 50 | 30 | 恰好 2 成功 28 余额不足、余额 0、不为负 | ✅ |

**「相同 requestId 并发」实测输出**：

```
并发线程数 = 10
成功次数 = 10        ← 10 个调用全部正常返回
失败次数 = 0         ← 无一抛出数据库异常
最终余额 = 900.00    ← 账户只扣款 1 次
流水条数 = 1         ← 数据库只有 1 条流水
```

---

## 11. 关键实现

### 11.1 事务

一次扣费会连续执行多步操作：

```text
查账户 → 跨月判断 → 币种换算 → 折扣计算 → 扣余额 → 更新 monthlyTotal → 写交易流水
```

这些步骤需要放在同一个事务中执行。任一步骤失败时，前面的数据库修改也需要回滚，避免出现余额已变化但交易流水未写入等不一致情况。

本项目使用 `TransactionTemplate` 控制核心扣费事务，原因见第 12 节。

### 11.2 悲观锁 `SELECT ... FOR UPDATE`

同一账户被多个线程同时扣费时，如果线程都先读到旧余额，再各自计算并写回，可能出现 `lost update`。

账户查询使用 `PESSIMISTIC_WRITE`：

```text
线程 A：锁定账户 → 读取 → 计算 → 更新 → 提交
线程 B：等待 A 提交 → 读取最新值 → 再执行
```

这样同一账户的写操作会按顺序处理，余额和 `monthlyTotal` 不会被并发覆盖。

### 11.3 `requestId` 唯一索引

`billing_transaction.request_id` 建有唯一索引 `uk_tx_request_id`。

即使多个线程同时通过应用层的 `findByRequestId()` 检查，数据库仍只允许其中一个线程插入该 `requestId`，从而保证同一笔业务不会写入多条交易流水。

### 11.4 数据幂等与响应幂等

这两个概念处理的是两个不同层面。

假设 10 个线程同时使用同一个 `requestId` 发起扣费：

- 如果数据库最终只有 1 条交易流水，并且账户只扣 1 次钱，这叫**数据幂等**；
- 如果另外 9 个重复请求也能拿到这笔已成功交易的结果，而不是收到唯一索引异常，这叫**响应幂等**。

可以对比为：

| 情况 | 数据库结果 | 调用方看到的结果 |
|---|---|---|
| 只有唯一索引 | 1 条交易流水，只扣 1 次 | 1 个成功，其余请求可能报唯一索引冲突 |
| 加上重复请求恢复 | 1 条交易流水，只扣 1 次 | 所有重复请求返回同一笔交易结果 |

### 11.5 BigDecimal 金额计算

金额统一使用 `BigDecimal`，数据库字段使用 `DECIMAL(15,2)`。

项目中统一：

- `setScale(2, RoundingMode.HALF_UP)` 处理两位小数；
- 使用 `compareTo()` 比较金额；
- 不使用 `float` / `double` 处理计费金额。

### 11.6 `requestId` 的业务含义

`requestId` 表示一笔业务交易，而不是每次 HTTP 调用的编号。

同一笔业务因网络超时、自动重试或重复提交再次发送时，应继续使用原来的 `requestId`；只有新的交易才使用新的 `requestId`。

---

## 12. 工程优化案例：从「数据幂等」到「响应幂等」

本节记录一次针对并发重复请求的优化过程。初版实现已经能够依靠 `requestId` 唯一索引保证同一笔业务不会重复落库，但在真实并发测试中，重复请求仍可能收到数据库唯一约束异常。针对这一问题，后续对事务边界和幂等恢复逻辑进行了调整。

### 12.1 初版：`check-then-insert` + 唯一索引

第一版扣费逻辑将「幂等检查 + 账户加锁 + 扣款 + 写流水」放在同一个 `@Transactional` 方法中：

```text
@Transactional deduct(requestId, ...) {
    ① findByRequestId(requestId)          // 幂等检查
    ② findByUserIdForUpdate(userId)       // 悲观锁账户
    ③ 计算并更新余额、monthlyTotal
    ④ insert billing_transaction          // request_id 唯一索引
}
```

顺序重复调用时，如果步骤 ① 已经查到相同 `requestId`，可以直接返回历史交易结果，因此单线程场景下能够正常实现重复请求识别。

但在并发场景中，多个线程可能在任一线程完成插入之前同时通过步骤 ①，这就是典型的 `check-then-insert` 竞争窗口。

### 12.2 并发测试暴露的问题

使用真实多线程测试，让 10 个线程以相同 `requestId` 同时调用扣费接口。

初版结果：

```text
1 个请求成功
9 个请求抛出 DataIntegrityViolationException
数据库最终只有 1 条交易流水
账户实际只扣款 1 次
```

从数据库结果看，唯一索引已经发挥作用：同一 `requestId` 只保留一条交易记录，因此没有发生重复扣款。

但从接口行为看，其余并发请求收到的是数据库唯一约束异常，而不是已经成功交易的业务结果。

因此，初版实现解决的是：

- **数据幂等**：保证同一笔业务不会重复写入、不会重复扣款；
- 但尚未实现完整的**响应幂等**：重复调用未必能获得与首次成功调用一致的响应。

### 12.3 根因：异常发生在事务内部

问题的关键不在唯一索引本身，而在事务边界。

并发场景下可能出现如下顺序：

```text
线程 A：findByRequestId → 未查到
线程 B：findByRequestId → 未查到

线程 A：完成扣款并插入 requestId = R
线程 B：尝试插入 requestId = R
         ↓
         唯一索引冲突
         ↓
         DataIntegrityViolationException
```

线程 B 的异常发生在 `@Transactional` 方法内部。此时当前事务会被标记为 `rollback-only`。

即使在同一个事务中捕获异常并再次执行：

```text
findByRequestId(requestId)
```

也无法把该事务恢复为可正常提交状态。事务最终仍需要回滚，因此不适合在这个事务内部继续完成“查询已成功交易并返回”的恢复逻辑。

这也是初版中数据库异常会直接暴露给调用方的原因。

### 12.4 优化：分离核心事务与幂等恢复逻辑

优化后，将外层请求处理和核心扣费事务拆开，并使用 `TransactionTemplate` 显式控制事务边界：

```text
deduct(requestId, ...)                         // 外层，无事务
    │
    ├─ ① findByRequestId(requestId)           // 快速幂等检查
    │      └─ 命中：直接返回历史交易结果
    │
    └─ ② TransactionTemplate.execute(...)     // 独立事务
           │
           └─ doDeduct(...)
                ├─ 悲观锁查询账户
                ├─ 币种换算与折扣计算
                ├─ 更新 balance / monthlyTotal
                └─ 写入 billing_transaction
```

如果步骤 ② 中发生唯一索引冲突：

```text
DataIntegrityViolationException
        ↓
核心扣费事务完整回滚
        ↓
异常返回到外层 deduct()
        ↓
再次 findByRequestId(requestId)
        ↓
读取其他并发请求已经成功提交的交易记录
        ↓
返回该交易对应的业务结果
```

这样，数据库唯一索引仍负责保证同一 `requestId` 只落一条流水，而外层逻辑负责在并发冲突后恢复为正常业务响应。

### 12.5 为什么使用 `TransactionTemplate`

这里使用 `TransactionTemplate`，主要是为了明确控制事务的开始、提交和回滚边界。

如果继续使用 `@Transactional`，并把事务方法拆成同一个类中的另一个方法，例如：

```java
public DeductResponse deduct(...) {
    return doDeduct(...);
}

@Transactional
public DeductResponse doDeduct(...) {
    ...
}
```

同类内部直接调用 `doDeduct()` 属于 Spring AOP 中的 **self-invocation**。这种调用不会经过 Spring 代理，因此 `@Transactional` 可能无法按预期创建独立事务边界。

使用 `TransactionTemplate` 可以避免这一代理调用问题，并使“核心扣费事务结束后，再执行幂等恢复查询”的时序更加明确。

### 12.6 优化结果

优化后重新执行 10 线程相同 `requestId` 并发测试：

```text
并发线程数 = 10
成功次数 = 10
失败次数 = 0
最终余额 = 900.00
交易流水数 = 1
```

最终行为为：

- 同一 `requestId` 只生成一条交易流水；
- 账户只发生一次实际扣款；
- 其余重复请求不会收到唯一索引异常；
- 重复请求返回已经成功提交的同一笔交易结果。

因此，该调整在原有**数据幂等**基础上补充了**响应幂等**，同时保留数据库唯一索引、悲观锁和事务对并发一致性的约束。
