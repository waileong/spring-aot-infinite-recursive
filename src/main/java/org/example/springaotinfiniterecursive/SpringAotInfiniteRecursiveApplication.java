package org.example.springaotinfiniterecursive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringAotInfiniteRecursiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAotInfiniteRecursiveApplication.class, args);
    }

    @Bean
    public TestBean testBean() {
        return new TestBean();
    }
}
