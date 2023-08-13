# ocpp-charge-point-simulator

## Introduction:
A charge point simulator based on the OCPP protocol.<br />
This simulator has been built using an [OCPP client-server Springboot library](https://github.com/ChargeTimeEU/Java-OCA-OCPP).
Once you run the application it connects to the server URL that is defined on the application.yml file. This connection takes place in the StartupConfiguration.java file, where we also do the initialization of our fake charge point settings. <br />

## How to run it locally
The project specs: Java 11, Springboot version 2.7.7 <br /> 
You only need to specify the configurations on the application.yml file:
- central-system-url: the url of the OCPP server
- charge-point-id
- connector-id (for now it only supports one single connector)
- charging-power: currently not being used. No smart charging has not been implemented yet, so it will remain static the whole time.
- meter-values.step: the amount of energy we want to send for every meter value.
- meter-values.frequency: it's supposed to be the initial meter value frequency; currently not being used.

## Dockerizing the app
Run the following commands:
- maven clean install
- docker build -t ocpp-simulator .
- docker run -p 8080:8080 ocpp-simulator


## API documentation
This project contains the Open API dependency and by default it runs locally on port 8080. <br /> 

The API details can be found on the swagger ui: http://localhost:8080/swagger-ui/index.html#/

## References
- [OCPP OFFICIAL DOCUMENTATION](https://www.oasis-open.org/committees/download.php/58944/ocpp-1.6.pdf)
- [OCPP client-server library](https://github.com/ChargeTimeEU/Java-OCA-OCPP)
