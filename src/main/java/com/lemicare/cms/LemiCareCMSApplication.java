package com.lemicare.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class LemiCareCMSApplication {

	public static void main(String[] args) {
		SpringApplication.run(LemiCareCMSApplication.class, args);
		System.out.println("Welcome to LemiCare CMS Application");
	}

}
