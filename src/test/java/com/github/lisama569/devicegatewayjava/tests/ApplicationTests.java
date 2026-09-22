package com.github.lisama569.devicegatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "gateway.port=0")
class ApplicationTests {
    @Test
    void contextLoads() {
    }
}
