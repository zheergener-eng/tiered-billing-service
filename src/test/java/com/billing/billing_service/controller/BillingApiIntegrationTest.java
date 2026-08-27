package com.billing.billing_service.controller;

import com.billing.billing_service.entity.Account;
import com.billing.billing_service.repository.AccountRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 计费 API 接口集成测试（MockMvc 发起真实 HTTP 请求，走完整 Controller → Service → MySQL 链路）。
 *
 * <p>类级别 {@link Transactional}：每个测试在独立事务中执行、结束后自动回滚，
 * 不污染数据库、测试之间相互隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    /** 在当前测试事务内创建测试账户（测试结束自动回滚） */
    private Account createAccount(Long userId, String balance, String monthlyTotal) {
        Account account = new Account();
        account.setUserId(userId);
        account.setBalance(new BigDecimal(balance));
        account.setMonthlyTotal(new BigDecimal(monthlyTotal));
        account.setMonth(YearMonth.now().toString());
        return accountRepository.saveAndFlush(account);
    }

    /** 把响应体解析为 JSON 树 */
    private JsonNode parse(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 金额断言：用 compareTo 比较，避免对 BigDecimal 整数/小数数值类型的敏感 */
    private void assertAmount(JsonNode node, String field, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(node.get(field).decimalValue()),
                "字段 " + field + " 期望 " + expected + "，实际 " + node.get(field));
    }

    /** 构造扣款请求 JSON */
    private String deductJson(Long userId, String amount, String currency, String requestId) {
        return String.format(
                "{\"userId\":%d,\"originalAmount\":%s,\"currency\":\"%s\",\"requestId\":\"%s\"}",
                userId, amount, currency, requestId);
    }

    @Test
    @DisplayName("POST /deduct：CNY 正常扣款")
    void shouldDeductCnyNormally() throws Exception {
        createAccount(3001L, "1000.00", "0.00");

        MvcResult result = mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(3001L, "100", "CNY", "api-cny-3001")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = parse(result);
        assertEquals("SUCCESS", node.get("status").asString());
        assertAmount(node, "finalDeductAmount", "100.00");
        assertAmount(node, "currentBalance", "900.00");
        assertEquals("扣费成功", node.get("message").asString());
    }

    @Test
    @DisplayName("POST /deduct：USD 按 7.2 汇率换算后扣款")
    void shouldDeductUsd() throws Exception {
        createAccount(3002L, "1000.00", "0.00");

        MvcResult result = mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(3002L, "10", "USD", "api-usd-3002")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = parse(result);
        assertEquals("SUCCESS", node.get("status").asString());
        assertAmount(node, "finalDeductAmount", "72.00");
        assertAmount(node, "currentBalance", "928.00");
    }

    @Test
    @DisplayName("POST /deduct：余额不足返回 INSUFFICIENT_FUNDS")
    void shouldReturnInsufficientFunds() throws Exception {
        createAccount(3003L, "50.00", "0.00");

        MvcResult result = mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(3003L, "100", "CNY", "api-insufficient-3003")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = parse(result);
        assertEquals("INSUFFICIENT_FUNDS", node.get("status").asString());
        assertAmount(node, "finalDeductAmount", "0.00");
        assertAmount(node, "currentBalance", "50.00");
        assertEquals("余额不足", node.get("message").asString());
    }

    @Test
    @DisplayName("POST /deduct：同一 requestId 重复调用只扣款一次")
    void shouldDeductOnlyOnceForSameRequestId() throws Exception {
        createAccount(3004L, "1000.00", "0.00");
        String requestBody = deductJson(3004L, "100", "CNY", "api-idempotent-3004");

        MvcResult first = mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk()).andReturn();

        // 两次扣款后余额仍为 900（而非 800），证明只扣了一次
        assertAmount(parse(first), "currentBalance", "900.00");
        assertAmount(parse(second), "currentBalance", "900.00");
        assertEquals("SUCCESS", parse(second).get("status").asString());
    }

    @Test
    @DisplayName("POST /deduct：JPY 非法币种返回 400")
    void shouldRejectJpyCurrency() throws Exception {
        createAccount(3005L, "1000.00", "0.00");

        MvcResult result = mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(3005L, "1000", "JPY", "api-jpy-3005")))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode node = parse(result);
        assertEquals(400, node.get("code").intValue());
        assertTrue(node.get("message").asString().contains("JPY"),
                "错误消息应包含币种代码 JPY");
    }

    @Test
    @DisplayName("GET /balance/{userId}：查询余额与本月累计消费")
    void shouldGetBalance() throws Exception {
        createAccount(3006L, "500.00", "150.00");

        MvcResult result = mockMvc.perform(get("/api/v1/billing/balance/3006"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = parse(result);
        assertEquals(3006L, node.get("userId").longValue());
        assertAmount(node, "balance", "500.00");
        assertAmount(node, "monthlyTotal", "150.00");
    }

    @Test
    @DisplayName("非法请求参数：金额≤0、requestId 为空、userId 为空均返回 400")
    void shouldRejectInvalidParams() throws Exception {
        // originalAmount = 0（必须大于 0）
        mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(3007L, "0", "CNY", "api-invalid-amount")))
                .andExpect(status().isBadRequest());

        // requestId 为空串
        mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deductJson(3007L, "100", "CNY", "")))
                .andExpect(status().isBadRequest());

        // userId 为 null
        mockMvc.perform(post("/api/v1/billing/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":null,\"originalAmount\":100,\"currency\":\"CNY\",\"requestId\":\"api-invalid-user\"}"))
                .andExpect(status().isBadRequest());
    }
}
