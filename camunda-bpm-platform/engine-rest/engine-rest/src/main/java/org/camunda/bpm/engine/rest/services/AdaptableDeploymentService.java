package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.DeploymentWithDefinitions;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentWithDefinitionsDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.runtime.ProcessInstance;

import javax.ws.rs.core.Response;
import java.io.*;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class AdaptableDeploymentService {

	private final PrintWriter writer;
	private final ProcessEngine engine;
	private final MultipartFormData multipartFormData;

	public AdaptableDeploymentService(ProcessEngine processEngine, MultipartFormData payload) {
		engine = processEngine;
		multipartFormData = payload;
		writer = initFileWriter();
	}

	public DeploymentWithDefinitionsDto deployAdaptable() {
		if (multipartFormData == null) {
			throwError("No data could be found in the Request. Deployment was stopped.");
			return null;
		}

		// 1. Get the data from the request.
		ProcessInstance originProcessInstance = extractOriginProcessInstance(multipartFormData);
		String originProcessDefinitionId = originProcessInstance.getProcessDefinitionId();
		ProcessDefinition originProcessDefinition = engine.getRepositoryService().getProcessDefinition(originProcessDefinitionId);

		// 1.1 Suspend the process instance that we want to migrate.
		engine.getRuntimeService().suspendProcessInstanceById(originProcessInstance.getId());
		writer.println("Suspended process instance with ID: " + originProcessInstance.getId() );

		// 2. Deploy the new model and fetch the new ProcessDefinition
		DeploymentWithDefinitions targetProcessDeploymentWithDefinitions = createAndDeployNewModel(originProcessDefinition.getDeploymentId());
		if (targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions() == null) {
			throwError("New new process definitions were deployed. Perhaps the deployment already exists?");
		}
		if (targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions().isEmpty()) {
			throwError("New model not deployed. Perhaps the deployment already exists?");
		}

		ProcessDefinition targetProcessDefinition = targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions().get(0);
		if (targetProcessDefinition == null) {
			throwError("Could not fetch the target ProcessDefinition. Perhaps the deployment already exists?");
		}
		writer.println("New process definition deployed with Process Definition ID: " + (targetProcessDefinition != null ? targetProcessDefinition.getId() : " "));

		// 3. Decide on the type of adaptation that needs to be performed.
		MigrationService migrationService = new MigrationService(engine, originProcessInstance.getId(), originProcessDefinition, targetProcessDefinition, writer);
		String activityId = extractActivityId(multipartFormData);

		if (activityId == null) {
			// no activity ID provided. Try to perform the migration.
			writer.println("No activity ID provided as a starting point.");
			migrationService.performAdaptableMigration();
		} else {
			writer.println("Activity ID provided as a starting point: " + activityId);
			migrationService.adaptableFromActivity(activityId);
		}

		writer.close();
		return DeploymentWithDefinitionsDto.fromDeployment(targetProcessDeploymentWithDefinitions);
	}

	private DeploymentWithDefinitions createAndDeployNewModel(String deploymentId) {
		DeploymentBuilderService deploymentBuilderService = new DeploymentBuilderService(engine, multipartFormData);
		DeploymentBuilder deploymentBuilder = deploymentBuilderService.createDeploymentBuilder();
		if (!deploymentBuilder.getResourceNames().isEmpty()) {
			DeploymentWithDefinitions deploymentWithDefinitions = deploymentBuilder.deployWithResult();
			if (deploymentWithDefinitions != null) {
				return deploymentWithDefinitions;
			}
		}

		throwError("The new process could not be deployed.");
		return null;
	}


	private ProcessInstance extractOriginProcessInstance(MultipartFormData multipartFormData) {

		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("process-instance-id") == null) {
			throwError("No Process Instance ID was given.");
		}

		String originProcessInstanceId = formParts.get("process-instance-id").getTextContent();
		if (originProcessInstanceId == null || originProcessInstanceId.equals(" ")) {
			throwError("No Process Instance ID was given.");
		}

		ProcessInstance originProcessInstance = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(originProcessInstanceId).singleResult();
		if (originProcessInstance == null) {
			throwError("No Process Instance could be found with the given ID.");
		}
		return originProcessInstance;
	}

	private String extractActivityId(MultipartFormData multipartFormData) {
		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("activity-id") == null) {
			return null;
		}

		String activityId = formParts.get("activity-id").getTextContent();
		if (activityId.equals(" ")) {
			return null;
		}
		return activityId;
	}

	private PrintWriter initFileWriter() {
		String timeStamp = new SimpleDateFormat("dd.MM.yyyy.HH.mm").format(new Date());
		String fileName = "logOutput_" + timeStamp + "_";
		PrintWriter printWriter;

		try {
			printWriter = new PrintWriter(new FileWriter(File.createTempFile(fileName, ".txt")));
		} catch (IOException e) {
			e.printStackTrace();
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "Could not write to file.");
		}

		printWriter.println(timeStamp + ": Adaptable process started.");
		return printWriter;
	}

	public void develop(String someString) {
		writer.println(someString);
		writer.close();
	}

	private void throwError(String errorMessage) {
		writer.close();
		throw new InvalidRequestException(Response.Status.BAD_REQUEST, errorMessage);
	}

}
