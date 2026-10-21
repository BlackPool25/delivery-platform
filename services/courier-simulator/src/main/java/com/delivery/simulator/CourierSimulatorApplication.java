package com.delivery.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CourierSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourierSimulatorApplication.class, args);
    }
}
