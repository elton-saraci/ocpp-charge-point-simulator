package com.ocpp.chargepointsimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChargePointSimulatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChargePointSimulatorApplication.class, args);
	}

}
