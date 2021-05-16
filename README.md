# camunda-adaptable-processes

To compile the source code, the following command can be used: `mvn -e <-o> <clean> package -DskipTests`.

Your local Maven file needs to be updated with the following: `/.m2/settings.xml` 

```
 </profiles>    
        <profile>
        	<id>camunda-bpm</id>
        	<repositories>
        	  	<repository>
        		    <id>camunda-bpm-nexus</id>
        		    <name>camunda-bpm-nexus</name>
        		    <url>https://app.camunda.com/nexus/content/groups/public</url>
        	 	</repository>
        	</repositories>
        </profile>
    </profiles>
    <activeProfiles>
        <activeProfile>camunda-bpm</activeProfile>
    </activeProfiles>        
```

### Making changes to the code:  
When making changes to the source code, you should keep in mind which package you are making changes to. For example, making changes to the REST API package, means that you have to compile the code in that specific folder: 
```
/camunda-bpm-platform/engine-rest
```
Then, the following jar file should be uploaded into the following folder:
```
.jar file:
/engine-rest/engine-rest-jaxrs2/target/camunda-engine-rest-jaxrs2-7.14.0.jar

folder:
/camunda-bpm-tomcat/server/apache-tomcat-9.0.36/webapps/engine-rest/WEB-INF/lib
```


These changes will only appear in the engine-rest project. So only the `http://localhost:8080/engine-rest` endpoint will be affected.

 In order to make changes to the Camunda app, you need to copy a different jar, the core api jar, into the camunda webapp folder:
```        
The jar file:
/engine-rest/engine-rest/target/camunda-engine-rest-core-7.14.0.jar

The folder:
/apache-tomcat-9.0.36/webapps/camunda/WEB-INF/lib/camunda-engine-rest-core-7.14.0.jar
```


### Using The test classes as an indication while developing
The source code of Camunda has a multitude of tests and test classes, in order to ensure the proper functionality of the code. As such, it was very helpful to investigate those test classes to learn how to set up different parts of the code and how to use the different functionalities:

```
src/test/java/org/camunda/bpm/engine/test/util/PluggableProcessEngineTest.java
    
    protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule();
    
    @Before
    public void initializeServices() {
        processEngine = engineRule.getProcessEngine();
        processEngineConfiguration = engineRule.getProcessEngineConfiguration();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        ...
    }
```

### Running only a specific test in a test file
`$ mvn test -Dtest=org.camunda.bpm.engine.test.api.repository.BpmnModelInstanceCmdTest test`     
