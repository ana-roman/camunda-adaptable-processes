package org.camunda.bpm.engine.rest.impl;

import org.camunda.bpm.engine.ProcessEngine;
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

	public AdaptableDeploymentService() {
		writer = getPrintWriter();
	}

	public DeploymentDto deployAdaptableProcess(MultipartFormData multipartFormData, ProcessEngine processEngine) {
		writer.println("Starting the adaptable process...");

		String originProcessInstanceId = extractProcessInstanceIdToMigrate(multipartFormData);
		// get the ProcessDefinitionID of the origin process Instance
		ProcessInstance originProcessInstance = processEngine.getRuntimeService().createProcessInstanceQuery().processInstanceId(originProcessInstanceId).singleResult();
		String originProcessDefinitionId = originProcessInstance.getProcessDefinitionId();
		writer.println("Origin process definition ID: " + originProcessDefinitionId);


		// Suspend the process instance that we want to migrate.
//		processEngine.getRuntimeService().suspendProcessInstanceById(originProcessInstanceId);
//
//		String targetProcessDefinitionId = "SimpleAdaptableProcess2:3:54e2548c-5c06-11eb-934f-00d861fc144c";
//
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


	private String extractProcessInstanceIdToMigrate(MultipartFormData multipartFormData) {
		if (multipartFormData == null) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Mapping could be found in the Request");
		}

		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("process-instance-id") == null) {
			writer.println("There was no Process Instance ID given");
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance ID was given.");
		}

		String processInstanceToSuspend = formParts.get("process-instance-id").getTextContent();
		if (processInstanceToSuspend == null || processInstanceToSuspend.equals(" ")) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance ID was given.");
		}

		return processInstanceToSuspend;
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
