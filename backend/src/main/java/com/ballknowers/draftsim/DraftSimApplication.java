package com.ballknowers.draftsim;

import com.ballknowers.draftsim.config.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        ScoringProperties.class,
        ShrinkageProperties.class,
        BoardProperties.class,
        PriorProperties.class
})
public class DraftSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(DraftSimApplication.class, args);
    }
}
