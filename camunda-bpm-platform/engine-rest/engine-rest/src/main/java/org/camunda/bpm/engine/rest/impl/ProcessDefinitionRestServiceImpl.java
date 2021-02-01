/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.rest.impl;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.exception.NotFoundException;
import org.camunda.bpm.engine.management.ProcessDefinitionStatistics;
import org.camunda.bpm.engine.management.ProcessDefinitionStatisticsQuery;
import org.camunda.bpm.engine.repository.DeleteProcessDefinitionsBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.rest.ProcessDefinitionRestService;
import org.camunda.bpm.engine.rest.dto.CountResultDto;
import org.camunda.bpm.engine.rest.dto.StatisticsResultDto;
import org.camunda.bpm.engine.rest.dto.repository.ProcessDefinitionDto;
import org.camunda.bpm.engine.rest.dto.repository.ProcessDefinitionQueryDto;
import org.camunda.bpm.engine.rest.dto.repository.ProcessDefinitionStatisticsResultDto;
import org.camunda.bpm.engine.rest.dto.repository.ProcessDefinitionSuspensionStateDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.exception.RestException;
import org.camunda.bpm.engine.rest.extracts.ProcessActivityExtract;
import org.camunda.bpm.engine.rest.extracts.ProcessTaskDto;
import org.camunda.bpm.engine.rest.sub.repository.ProcessDefinitionResource;
import org.camunda.bpm.engine.rest.sub.repository.impl.ProcessDefinitionResourceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

public class ProcessDefinitionRestServiceImpl extends AbstractRestProcessEngineAware implements ProcessDefinitionRestService {

	public ProcessDefinitionRestServiceImpl(String engineName, ObjectMapper objectMapper) {
    super(engineName, objectMapper);
  }

	public ProcessDefinitionResource getProcessDefinitionByKey(String processDefinitionKey) {

	  ProcessDefinition processDefinition = getProcessEngine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(processDefinitionKey)
        .withoutTenantId()
        .latestVersion()
        .singleResult();

    if(processDefinition == null){
      String errorMessage = String.format("No matching process definition with key: %s and no tenant-id", processDefinitionKey);
      throw new RestException(Status.NOT_FOUND, errorMessage);

    } else {
      return getProcessDefinitionById(processDefinition.getId());
    }
	}

	public ProcessDefinitionResource getProcessDefinitionByKeyAndTenantId(String processDefinitionKey, String tenantId) {

    ProcessDefinition processDefinition = getProcessEngine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(processDefinitionKey)
        .tenantIdIn(tenantId)
        .latestVersion()
        .singleResult();

    if (processDefinition == null) {
      String errorMessage = String.format("No matching process definition with key: %s and tenant-id: %s", processDefinitionKey, tenantId);
      throw new RestException(Status.NOT_FOUND, errorMessage);

    } else {
      return getProcessDefinitionById(processDefinition.getId());
    }
  }

  @Override
  public ProcessDefinitionResource getProcessDefinitionById(
      String processDefinitionId) {
    return new ProcessDefinitionResourceImpl(getProcessEngine(), processDefinitionId, relativeRootResourcePath, getObjectMapper());
  }

  @Override
	public List<ProcessDefinitionDto> getProcessDefinitions(UriInfo uriInfo,
	    Integer firstResult, Integer maxResults) {
    ProcessDefinitionQueryDto queryDto = new ProcessDefinitionQueryDto(getObjectMapper(), uriInfo.getQueryParameters());
	  List<ProcessDefinitionDto> definitions = new ArrayList<ProcessDefinitionDto>();

	  ProcessEngine engine = getProcessEngine();
	  ProcessDefinitionQuery query = queryDto.toQuery(engine);

	  List<ProcessDefinition> matchingDefinitions = null;

	  if (firstResult != null || maxResults != null) {
	    matchingDefinitions = executePaginatedQuery(query, firstResult, maxResults);
	  } else {
	    matchingDefinitions = query.list();
	  }

	  for (ProcessDefinition definition : matchingDefinitions) {
	    ProcessDefinitionDto def = ProcessDefinitionDto.fromProcessDefinition(definition);
	    definitions.add(def);
	  }
	  return definitions;
	}

	private List<ProcessDefinition> executePaginatedQuery(ProcessDefinitionQuery query, Integer firstResult, Integer maxResults) {
	  if (firstResult == null) {
	    firstResult = 0;
	  }
	  if (maxResults == null) {
	    maxResults = Integer.MAX_VALUE;
	  }
	  return query.listPage(firstResult, maxResults);
	}

	@Override
  public CountResultDto getProcessDefinitionsCount(UriInfo uriInfo) {
	  ProcessDefinitionQueryDto queryDto = new ProcessDefinitionQueryDto(getObjectMapper(), uriInfo.getQueryParameters());

	  ProcessEngine engine = getProcessEngine();
    ProcessDefinitionQuery query = queryDto.toQuery(engine);

    long count = query.count();
    CountResultDto result = new CountResultDto();
    result.setCount(count);
    return result;
  }


  @Override
  public List<StatisticsResultDto> getStatistics(Boolean includeFailedJobs, Boolean includeRootIncidents, Boolean includeIncidents, String includeIncidentsForType) {
    if (includeIncidents != null && includeIncidentsForType != null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "Only one of the query parameter includeIncidents or includeIncidentsForType can be set.");
    }

    if (includeIncidents != null && includeRootIncidents != null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "Only one of the query parameter includeIncidents or includeRootIncidents can be set.");
    }

    if (includeRootIncidents != null && includeIncidentsForType != null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "Only one of the query parameter includeRootIncidents or includeIncidentsForType can be set.");
    }

    ManagementService mgmtService = getProcessEngine().getManagementService();
    ProcessDefinitionStatisticsQuery query = mgmtService.createProcessDefinitionStatisticsQuery();

    if (includeFailedJobs != null && includeFailedJobs) {
      query.includeFailedJobs();
    }

    if (includeIncidents != null && includeIncidents) {
      query.includeIncidents();
    } else if (includeIncidentsForType != null) {
      query.includeIncidentsForType(includeIncidentsForType);
    } else if (includeRootIncidents != null && includeRootIncidents) {
      query.includeRootIncidents();
    }

    List<ProcessDefinitionStatistics> queryResults = query.unlimitedList();

    List<StatisticsResultDto> results = new ArrayList<StatisticsResultDto>();
    for (ProcessDefinitionStatistics queryResult : queryResults) {
      StatisticsResultDto dto = ProcessDefinitionStatisticsResultDto.fromProcessDefinitionStatistics(queryResult);
      results.add(dto);
    }

    return results;
  }

  public void updateSuspensionState(ProcessDefinitionSuspensionStateDto dto) {
    if (dto.getProcessDefinitionId() != null) {
      String message = "Only processDefinitionKey can be set to update the suspension state.";
      throw new InvalidRequestException(Status.BAD_REQUEST, message);
    }

    try {
      dto.updateSuspensionState(getProcessEngine());

    } catch (IllegalArgumentException e) {
      String message = String.format("Could not update the suspension state of Process Definitions due to: %s", e.getMessage()) ;
      throw new InvalidRequestException(Status.BAD_REQUEST, e, message);
    }
  }

  @Override
  public void deleteProcessDefinitionsByKey(String processDefinitionKey, boolean cascade, boolean skipCustomListeners, boolean skipIoMappings) {
    RepositoryService repositoryService = processEngine.getRepositoryService();

    DeleteProcessDefinitionsBuilder builder = repositoryService.deleteProcessDefinitions()
      .byKey(processDefinitionKey);

    deleteProcessDefinitions(builder, cascade, skipCustomListeners, skipIoMappings);
  }

  @Override
  public void deleteProcessDefinitionsByKeyAndTenantId(String processDefinitionKey, boolean cascade, boolean skipCustomListeners, boolean skipIoMappings, String tenantId) {
    RepositoryService repositoryService = processEngine.getRepositoryService();

    DeleteProcessDefinitionsBuilder builder = repositoryService.deleteProcessDefinitions()
      .byKey(processDefinitionKey)
      .withTenantId(tenantId);

    deleteProcessDefinitions(builder, cascade, skipCustomListeners, skipIoMappings);
  }

  protected void deleteProcessDefinitions(DeleteProcessDefinitionsBuilder builder, boolean cascade, boolean skipCustomListeners, boolean skipIoMappings) {
    if (skipCustomListeners) {
      builder = builder.skipCustomListeners();
    }

    if (cascade) {
      builder = builder.cascade();
    }

    if (skipIoMappings) {
      builder = builder.skipIoMappings();
    }

    try {
      builder.delete();
    } catch (NotFoundException e) { // rewrite status code from bad request (400) to not found (404)
      throw new InvalidRequestException(Status.NOT_FOUND, e.getMessage());
    }
  }


	@Override
//  public List<ProcessActivityExtract> getProcessActivitiesByProcessDefinitionId(String processDefinitionId) {
//  public HashMap<String, List<ProcessTaskDto>> getProcessActivitiesByProcessDefinitionId(String processDefinitionId) {
  public HashMap<String, List<?>> getProcessActivitiesByProcessDefinitionId(String originProcessDefId, String targetProcessDefId) {
//		String secondProcessDefID = "ScriptAdaptableProcess:16:c9c13914-6249-11eb-bce9-00d861fc144c";
		HashMap<String, List<?>> output = new HashMap<>();
//		Collection<Task> firstProcessTasks = getTaskListForProcessDefinition(getProcessEngine().getRepositoryService().getProcessDefinition(originProcessDefId));
//		Collection<Task> secondProcessTasks = getTaskListForProcessDefinition(getProcessEngine().getRepositoryService().getProcessDefinition(targetProcessDefId));
//
//		List<Task> changed = firstProcessTasks.stream().filter(
//			originTask -> secondProcessTasks.stream().noneMatch(targetTask -> haveEqualData(originTask, targetTask))
//		).collect(Collectors.toList());

//		output.put("tasks", tasks.stream().map(ProcessTaskDto::new).collect(Collectors.toList()));
//		output.put("changed activities", changed.stream().map(ProcessTaskDto::new).collect(Collectors.toList()));

		List<String> activeActivities = getProcessEngine().getRuntimeService().getActiveActivityIds(originProcessDefId);
//		output.put("active activities", activeActivities.stream().map(ProcessTaskDto::new).collect(Collectors.toList()));

		if (activeActivities.isEmpty()) {
			output.put("active activities empty", null);
			return output;
		}
		String activeActivityId = activeActivities.get(0);


		ProcessInstance originProcessInstance = getProcessEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(originProcessDefId).singleResult();
		Collection<Task> taskList = getTaskListForProcessDefinition(getProcessEngine().getRepositoryService().getProcessDefinition(originProcessInstance.getProcessDefinitionId()));
		Task activeTask = taskList.stream().filter(task-> task.getId().equals(activeActivityId)).collect(Collectors.toList()).get(0);
//		output.put("active Task:", Collections.singletonList(activeTask.getName()));

		SequenceFlow sequenceFlow = activeTask.getIncoming().iterator().next();
		FlowNode previousNode = sequenceFlow.getSource();
//		output.put("previous node", Collections.singletonList(previousNode.getName()));
		output.put("sequence flow", Collections.singletonList(sequenceFlow.getId()));

		processEngine.getRuntimeService().createProcessInstanceModification(originProcessInstance.getId())
			.cancelAllForActivity(activeActivityId)
			.startAfterActivity(previousNode.getId())
			.execute();

//		output.put("the new active activity", processEngine.getRuntimeService().getActiveActivityIds(originProcessDefId));
//
//		processEngine.getRuntimeService().createProcessInstanceModification(originProcessInstance.getId())
//			.cancelAllForActivity(previousNode.getId())
//			.startTransition(sequenceFlow.getId())
//			.execute();
//
//		output.put("active activity after starting transition", processEngine.getRuntimeService().getActiveActivityIds(originProcessDefId));


		return output;
	}

	private FlowNode getPreviousFlowNode(Task task) {
		return task.getIncoming().iterator().next().getSource();
	}

	private Activity getPreviousActivity(Task changedTask) {
		FlowNode parentTask = changedTask;
		while (true) {
			try {
				parentTask = parentTask.getIncoming().iterator().next().getSource();
				if (parentTask instanceof Activity) {
					return (Activity) parentTask;
				}
			} catch (BpmnModelException e) {
				throw new BpmnModelException("Unable to determine an unique previous activity of " + changedTask.getId(), e);
			}
		}
	}

	private Collection<Task> getTaskListForProcessDefinition(ProcessDefinition processDefinition) {
		BpmnModelInstance bpmnModelInstance = getProcessEngine().getRepositoryService().getBpmnModelInstance(processDefinition.getId());
		Collection<Task> tasks = bpmnModelInstance.getModelElementsByType(Task.class);
		if (tasks.isEmpty()) {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "No tasks found for Process Definition: " + processDefinition.getId());
		}

		return tasks;
	}

	private boolean haveEqualData(Task origin, Task target) {
		return (origin.getId().equals(target.getId())) &&
			(origin.getName().equals(target.getName())) &&
			(origin.getElementType().getTypeName().equals(
				target.getElementType().getTypeName()));
	}



}
