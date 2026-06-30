package br.com.bora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BoraApplication {
    public static void main(String[] args) {
        SpringApplication.run(BoraApplication.class, args);
    }
}
