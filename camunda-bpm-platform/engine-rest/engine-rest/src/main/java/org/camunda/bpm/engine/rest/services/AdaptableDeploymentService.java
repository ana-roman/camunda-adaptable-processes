package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.runtime.ProcessInstance;

import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class AdaptableDeploymentService {

	public static PrintWriter writer;
	private final ProcessEngine engine;
	private final MultipartFormData multipartFormData;

	public AdaptableDeploymentService(ProcessEngine processEngine, MultipartFormData payload) {
		engine = processEngine;
		writer = getPrintWriter();
		multipartFormData = payload;
	}

	public DeploymentDto deployAdaptableProcess() {

		// 1. Suspend the process instance that we want to migrate.
		ProcessInstance originProcessInstance = extractOriginProcessInstanceId(multipartFormData);
		String originProcessDefinitionId = originProcessInstance.getProcessDefinitionId();
		engine.getRuntimeService().suspendProcessInstanceById(originProcessInstance.getId());

		// 2. Deploy the new process.
		DeploymentDto deploymentDto = createAndDeployNewProcess();


//		MigrationPlan migrationPlan = processEngine.getRuntimeService()
//			.createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
//			.mapEqualActivities()
//			.updateEventTriggers()
//			.build();
//
//		writer.println("Migration plan has been created");
//		MigrationPlanExecutionBuilder builder = processEngine.getRuntimeService()
//			.newMigration(migrationPlan)
//			.processInstanceIds(originProcessInstanceId);
//		writer.println("Migration is about to be executed...");
//		builder.execute();
//		writer.println("Migration was executed");
//		processEngine.getRepositoryService().activateProcessDefinitionById(originProcessDefinitionId, true, null);
//		processEngine.getRepositoryService().activateProcessDefinitionById(targetProcessDefinitionId, true, null);
//		writer.println("Process definitions were reactivated");

		writer.close();
		return null;
	}

	private DeploymentDto createAndDeployNewProcess() {
		DeploymentBuilderService deploymentBuilderService = new DeploymentBuilderService(engine, multipartFormData);
		DeploymentBuilder deploymentBuilder = deploymentBuilderService.createDeploymentBuilder();
		if (!deploymentBuilder.getResourceNames().isEmpty()) {
			Deployment deployment = deploymentBuilder.deploy();
			if (deployment != null) {
				return DeploymentDto.fromDeployment(deployment);
			}
		}

		throw new InvalidRequestException(Response.Status.BAD_REQUEST, "The new process could not be deployed.");
	}


	private ProcessInstance extractOriginProcessInstanceId(MultipartFormData multipartFormData) {
		if (multipartFormData == null) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Mapping could be found in the Request");
		}

		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("process-instance-id") == null) {
			writer.println("There was no Process Instance ID given");
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance ID was given.");
		}

		String originProcessInstanceId = formParts.get("process-instance-id").getTextContent();
		if (originProcessInstanceId == null || originProcessInstanceId.equals(" ")) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance ID was given.");
		}

		ProcessInstance originProcessInstance = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(originProcessInstanceId).singleResult();
		if (originProcessInstance == null) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance could be found with the given ID.");
		}
		return originProcessInstance;
	}

	private PrintWriter getPrintWriter() {
		File file = new File("text/output_file4.txt");
		try {
			boolean newfile = file.createNewFile();
			return new PrintWriter(new FileWriter(file));
		} catch (Exception e) {
			throw new InvalidRequestException(Response.Status.SEE_OTHER, "Cant create file");
		}
	}

}
