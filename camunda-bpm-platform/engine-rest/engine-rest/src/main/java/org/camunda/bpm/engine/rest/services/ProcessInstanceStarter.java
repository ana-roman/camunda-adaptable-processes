package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Gateway;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;

import java.io.PrintWriter;
import java.util.Map;

public class ProcessInstanceStarter {

	private final PrintWriter writer;
	private final ProcessEngine engine;
	private final String processDefinitionId;
	private ProcessInstance processInstance;

	public ProcessInstanceStarter(ProcessEngine engine, String processDefinitionId, PrintWriter writer) {
		this.writer = writer;
		this.engine = engine;
		this.processDefinitionId = processDefinitionId;
	}

	public void startProcessAtNode(String nodeId) {
		writer.println("Starting new process at node: " + nodeId);


	}

	public void startProcessInstanceAtTransition(SequenceFlow sequenceFlow, Map<String, Object> variables) {
		writer.println("Starting new process at transition: " + sequenceFlow.getId());
		processInstance = engine.getRuntimeService()
			.createProcessInstanceById(processDefinitionId)
			.setVariables(variables)
			.startTransition(sequenceFlow.getId()) // TODO: mention that this doesn't consider flow conditions.
			.executeWithVariablesInReturn(false, false);

		writer.println("The adaptable process was deployed with Process Instance ID: " + processInstance.getId());
	}


	public void startProcessInstanceAtGateway(Gateway gateway, Map<String, Object> variables) {
		if (gateway.getIncoming().size() > 1) {
			writer.println("Starting new process at (after) gateway: " + gateway.getId());
			// Start a new process instance of the new process definition
			processInstance = engine.getRuntimeService()
				.createProcessInstanceById(processDefinitionId)
				.setVariables(variables)
				.startAfterActivity(gateway.getId()) // this will consider flow conditions too
				.executeWithVariablesInReturn(false, false);
		} else {
			writer.println("Starting new process at (before) gateway: " + gateway.getId());
			// Start a new process instance of the new process definition
			processInstance = engine.getRuntimeService()
				.createProcessInstanceById(processDefinitionId)
				.setVariables(variables)
				.startBeforeActivity(gateway.getId()) // this will consider flow conditions too
				.executeWithVariablesInReturn(false, false);
		}

		writer.println("The adaptable process was deployed with Process Instance ID: " + processInstance.getId());
	}

}
