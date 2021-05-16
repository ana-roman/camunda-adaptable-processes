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

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.*;
import org.camunda.bpm.engine.rest.DeploymentRestService;
import org.camunda.bpm.engine.rest.dto.CountResultDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentQueryDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentWithDefinitionsDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.rest.services.AdaptableDeploymentService;
import org.camunda.bpm.engine.rest.services.DeploymentBuilderService;
import org.camunda.bpm.engine.rest.sub.repository.DeploymentResource;
import org.camunda.bpm.engine.rest.sub.repository.impl.DeploymentResourceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.Task;

public class DeploymentRestServiceImpl extends AbstractRestProcessEngineAware implements DeploymentRestService {

  public DeploymentRestServiceImpl(String engineName, ObjectMapper objectMapper) {
    super(engineName, objectMapper);
  }

  public DeploymentResource getDeployment(String deploymentId) {
    return new DeploymentResourceImpl(getProcessEngine().getName(), deploymentId, relativeRootResourcePath, getObjectMapper());
  }

  public List<DeploymentDto> getDeployments(UriInfo uriInfo, Integer firstResult, Integer maxResults) {
    DeploymentQueryDto queryDto = new DeploymentQueryDto(getObjectMapper(), uriInfo.getQueryParameters());

    ProcessEngine engine = getProcessEngine();
    DeploymentQuery query = queryDto.toQuery(engine);

    List<Deployment> matchingDeployments;
    if (firstResult != null || maxResults != null) {
      matchingDeployments = executePaginatedQuery(query, firstResult, maxResults);
    } else {
      matchingDeployments = query.list();
    }

    List<DeploymentDto> deployments = new ArrayList<DeploymentDto>();
    for (Deployment deployment : matchingDeployments) {
      DeploymentDto def = DeploymentDto.fromDeployment(deployment);
      deployments.add(def);
    }
    return deployments;
  }

  private List<Deployment> executePaginatedQuery(DeploymentQuery query, Integer firstResult, Integer maxResults) {
    if (firstResult == null) {
      firstResult = 0;
    }
    if (maxResults == null) {
      maxResults = Integer.MAX_VALUE;
    }
    return query.listPage(firstResult, maxResults);
  }

  public CountResultDto getDeploymentsCount(UriInfo uriInfo) {
    DeploymentQueryDto queryDto = new DeploymentQueryDto(getObjectMapper(), uriInfo.getQueryParameters());

    ProcessEngine engine = getProcessEngine();
    DeploymentQuery query = queryDto.toQuery(engine);

    long count = query.count();
    CountResultDto result = new CountResultDto();
    result.setCount(count);
    return result;
  }

  public DeploymentWithDefinitionsDto createDeployment(UriInfo uriInfo, MultipartFormData payload) throws IOException {
    DeploymentBuilderService deploymentBuilderService = new DeploymentBuilderService(getProcessEngine(), payload);
    DeploymentBuilder deploymentBuilder = deploymentBuilderService.createDeploymentBuilder();

    if(!deploymentBuilder.getResourceNames().isEmpty()) {
      DeploymentWithDefinitions deployment = deploymentBuilder.deployWithResult();

      DeploymentWithDefinitionsDto deploymentDto = DeploymentWithDefinitionsDto.fromDeployment(deployment);

      URI uri = uriInfo.getBaseUriBuilder()
        .path(relativeRootResourcePath)
        .path(DeploymentRestService.PATH)
        .path(deployment.getId())
        .build();

      // GET
      deploymentDto.addReflexiveLink(uri, HttpMethod.GET, "self");

      return deploymentDto;

    } else {
      throw new InvalidRequestException(Status.BAD_REQUEST, "No deployment resources contained in the form upload.");
    }
  }

  public DeploymentWithDefinitionsDto deployAdaptable(UriInfo uriInfo, MultipartFormData multipartFormData) {
    AdaptableDeploymentService service = new AdaptableDeploymentService(getProcessEngine(), multipartFormData);
    return service.deployAdaptable();
  }

  public  HashMap<String, List<String>> develop2(String originProcessInstanceId, String target) {
    HashMap<String, List<String>> list = new HashMap<>();

    ProcessInstance originProcessInstance = processEngine.getRuntimeService().createProcessInstanceQuery().processInstanceId(originProcessInstanceId).singleResult();

    Collection<Task> originTasks = getTaskListForProcessDefinition(originProcessInstance.getProcessDefinitionId());
    Collection<Task> targetTasks = getTaskListForProcessDefinition(target);

    List<Task> changedTasksInOrigin = getReplacedTasks(originTasks, targetTasks);
    if (changedTasksInOrigin != null) {
      list.put("Changed tasks in origin:", changedTasksInOrigin.stream().map(BaseElement::getId).collect(Collectors.toList()));
    }
    List<Task> changedTasksInTarget = getReplacedTasks(targetTasks, originTasks);
    if (changedTasksInTarget != null) {
      list.put("Changed tasks in target:", changedTasksInTarget.stream().map(BaseElement::getId).collect(Collectors.toList()));
    }

    List<String> activeActivities = processEngine.getRuntimeService().getActiveActivityIds(originProcessInstanceId);
    list.put("Active activities in origin: ", activeActivities);
    List<String> activitiesToMigrate = new ArrayList<>();

    if (changedTasksInTarget != null) {
      changedTasksInTarget.forEach(changedTask -> {
        if (changedTasksInOrigin != null) {
          Optional<Task> optionalTask = changedTasksInOrigin.stream().filter(task -> task.getId().equals(changedTask.getId())).findAny();
          if (optionalTask.isPresent()) {
            if (changedTask.getElementType().getTypeName().equals(optionalTask.get().getElementType().getTypeName())) {
              activitiesToMigrate.add(changedTask.getId());
            }
          }
        }
      });
    }


    list.put("Activities to migrate: ", activitiesToMigrate);
    return list;

  }

  public ResponseDto develop() {

    (new AdaptableDeploymentService(getProcessEngine(), null)).develop("HELLO");
    return new ResponseDto("key", "value");
  }

  @Override
  public String deleteDeploymentByKey(String processDefinitionKey) {
    RepositoryService repositoryService = getProcessEngine().getRepositoryService();
    List<ProcessDefinition> processDefinitionsList = repositoryService.createProcessDefinitionQuery().processDefinitionKey(processDefinitionKey).list();

    for (ProcessDefinition processDefinition: processDefinitionsList) {
      String deploymentId = processDefinition.getDeploymentId();
      Deployment deployment = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult();
      if (deployment == null) {
        throw new InvalidRequestException(Status.NOT_FOUND, "Deployment with id '" + deploymentId + "' do not exist");
      }

      repositoryService.deleteDeployment(deploymentId, true);
    }
    return "success";
  }


  public class ResponseDto {
    public String message;
    public String data;

    public ResponseDto(String name, String data) {
      this.message = name;
      this.data = data;
    }
  }


    private Collection<Task> getTaskListForProcessDefinition(String processDefinition) {
    BpmnModelInstance bpmnModelInstance = processEngine.getRepositoryService().getBpmnModelInstance(processDefinition);
    Collection<Task> tasks = bpmnModelInstance.getModelElementsByType(Task.class);
    if (tasks.isEmpty()) {
      throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "No tasks found for Process Definition: " + processDefinition);
    }

    return tasks;

  }

  private boolean haveEqualData(Task origin, Task target) {
    return (origin.getId().equals(target.getId())) &&
      (origin.getName().equals(target.getName())) &&
      (origin.getElementType().getTypeName().equals(
        target.getElementType().getTypeName()));
  }

  private List<Task> getReplacedTasks(Collection<Task> origin, Collection<Task> target) {
    List<Task> changed = origin.stream().filter(
      originTask -> target.stream().noneMatch(targetTask -> haveEqualData(originTask, targetTask))
    ).collect(Collectors.toList());
    if (changed.isEmpty()) {
      return null;
    }
    return changed;
  }



}
