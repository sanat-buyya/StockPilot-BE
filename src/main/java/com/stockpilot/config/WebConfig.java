package com.stockpilot.config;

import com.stockpilot.inventory.Reason;
import com.stockpilot.order.OrderStatus;
import com.stockpilot.workspace.Role;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Query/path parameters accept the same lowercase values as the JSON bodies (e.g. status=cancelled). */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, OrderStatus.class, OrderStatus::fromDb);
        registry.addConverter(String.class, Reason.class, Reason::fromDb);
        registry.addConverter(String.class, Role.class, Role::fromDb);
    }
}
