package com.forgeddetect;

import com.forgeddetect.service.CaseProperties;
import com.forgeddetect.service.ComplianceProperties;
import com.forgeddetect.service.DecisionThresholdProperties;
import com.forgeddetect.service.PrescreenProperties;
import com.forgeddetect.service.VideoProperties;
import com.forgeddetect.service.WellbeingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    DecisionThresholdProperties.class, ComplianceProperties.class, PrescreenProperties.class, CaseProperties.class,
    VideoProperties.class, WellbeingProperties.class
})
public class ForgedMediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgedMediaApplication.class, args);
    }
}
