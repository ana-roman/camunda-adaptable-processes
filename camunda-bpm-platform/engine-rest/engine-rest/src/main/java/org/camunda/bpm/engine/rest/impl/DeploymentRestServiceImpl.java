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

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.batch.Batch;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.repository.*;
import org.camunda.bpm.engine.rest.DeploymentRestService;
import org.camunda.bpm.engine.rest.dto.CountResultDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentQueryDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentWithDefinitionsDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData.FormPart;
import org.camunda.bpm.engine.rest.sub.repository.DeploymentResource;
import org.camunda.bpm.engine.rest.sub.repository.impl.DeploymentResourceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.camunda.bpm.model.bpmn.instance.Task;

public class DeploymentRestServiceImpl extends AbstractRestProcessEngineAware implements DeploymentRestService {

  public final static String DEPLOYMENT_NAME = "deployment-name";
  public final static String ENABLE_DUPLICATE_FILTERING = "enable-duplicate-filtering";
  public final static String DEPLOY_CHANGED_ONLY = "deploy-changed-only";
  public final static String DEPLOYMENT_SOURCE = "deployment-source";
  public final static String TENANT_ID = "tenant-id";

  protected static final Set<String> RESERVED_KEYWORDS = new HashSet<String>();

  static {
    RESERVED_KEYWORDS.add(DEPLOYMENT_NAME);
    RESERVED_KEYWORDS.add(ENABLE_DUPLICATE_FILTERING);
    RESERVED_KEYWORDS.add(DEPLOY_CHANGED_ONLY);
    RESERVED_KEYWORDS.add(DEPLOYMENT_SOURCE);
    RESERVED_KEYWORDS.add(TENANT_ID);
  }

  public DeploymentRestServiceImpl(String engineName, ObjectMapper objectMapper) {
    super(engineName, objectMapper);
  }

  public DeploymentResource getDeployment(String deploymentId) {
    return new DeploymentResourceImpl(getProcessEngine().getName(), deploymentId, relativeRootResourcePath, getObjectMapper());
  }

  private PrintWriter getPrintWriter() {
    File file = new File("text/output_file3.txt");
    try {
      boolean newfile = file.createNewFile();
      return new PrintWriter(new FileWriter(file));
    } catch (Exception e) {
      throw new InvalidRequestException(Status.NOT_ACCEPTABLE, "Cant create file");
    }
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

  private DeploymentBuilder extractDeploymentInformation(MultipartFormData payload) {
    DeploymentBuilder deploymentBuilder = getProcessEngine().getRepositoryService().createDeployment();

    Set<String> partNames = payload.getPartNames();

    for (String name : partNames) {
      FormPart part = payload.getNamedPart(name);

      if (!RESERVED_KEYWORDS.contains(name)) {
        String fileName = part.getFileName();
        if (fileName != null) {
          deploymentBuilder.addInputStream(part.getFileName(), new ByteArrayInputStream(part.getBinaryContent()));
        } else {
          throw new InvalidRequestException(Status.BAD_REQUEST, "No file name found in the deployment resource described by form parameter '" + fileName + "'.");
        }
      }
    }

    FormPart deploymentName = payload.getNamedPart(DEPLOYMENT_NAME);
    if (deploymentName != null) {
      deploymentBuilder.name(deploymentName.getTextContent());
    }

    FormPart deploymentSource = payload.getNamedPart(DEPLOYMENT_SOURCE);
    if (deploymentSource != null) {
      deploymentBuilder.source(deploymentSource.getTextContent());
    }

    FormPart deploymentTenantId = payload.getNamedPart(TENANT_ID);
    if (deploymentTenantId != null) {
      deploymentBuilder.tenantId(deploymentTenantId.getTextContent());
    }

    extractDuplicateFilteringForDeployment(payload, deploymentBuilder);
    return deploymentBuilder;
  }

  private void extractDuplicateFilteringForDeployment(MultipartFormData payload, DeploymentBuilder deploymentBuilder) {
    boolean enableDuplicateFiltering = false;
    boolean deployChangedOnly = false;

    FormPart deploymentEnableDuplicateFiltering = payload.getNamedPart(ENABLE_DUPLICATE_FILTERING);
    if (deploymentEnableDuplicateFiltering != null) {
      enableDuplicateFiltering = Boolean.parseBoolean(deploymentEnableDuplicateFiltering.getTextContent());
    }

    FormPart deploymentDeployChangedOnly = payload.getNamedPart(DEPLOY_CHANGED_ONLY);
    if (deploymentDeployChangedOnly != null) {
      deployChangedOnly = Boolean.parseBoolean(deploymentDeployChangedOnly.getTextContent());
    }

    // deployChangedOnly overrides the enableDuplicateFiltering setting
    if (deployChangedOnly) {
      deploymentBuilder.enableDuplicateFiltering(true);
    } else if (enableDuplicateFiltering) {
      deploymentBuilder.enableDuplicateFiltering(false);
    }
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
    DeploymentBuilder deploymentBuilder = extractDeploymentInformation(payload);

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


  // to keep in mind when deploying from the modeler:
  // there will be multiple versions that have the same process definition key
  // so i will need to take the latest version and migrate that one!
  // this is what they did in the tutorial
  @Override
  public String deployAdaptable(UriInfo uriInfo, MultipartFormData payload) {
    // Step 1: suspend the instance that is running (explain order)
    // Step 2: deploy the new model. - already done for this test
    // Step 3: perform the migration.
//    DeploymentBuilder deploymentBuilder = extractDeploymentInformation(payload);
//    DeploymentWithDefinitions deployment = deploymentBuilder.deployWithResult();

    PrintWriter writer = getPrintWriter();
    writer.println("Starting the adaptable process...");

    String originProcessDefinitionId = "SimpleAdaptableProcess2:2:394ed9f9-5c06-11eb-934f-00d861fc144c";
    String targetProcessDefinitionId = "SimpleAdaptableProcess2:3:54e2548c-5c06-11eb-934f-00d861fc144c";

    ProcessDefinition originProcessDefinition = processEngine.getRepositoryService().getProcessDefinition(originProcessDefinitionId);
    ProcessDefinition targetProcessDefinition = processEngine.getRepositoryService().getProcessDefinition(targetProcessDefinitionId);

//    To handle variables, I can do this right here and set them right at the start of the execution somehow,
//    or right after a process instance is started
//    Map<String, Object> originExecutionVariables = processEngine.getRuntimeService().getVariables("executionId");
//    Map<String, Object> targetexecutionVariables = processEngine.getRuntimeService().setVariablesAsync();

    // Do I HAVE to suspend this here? Maybe this bugs out
//    if (!originProcessDefinition.isSuspended()) {
//      writer.println("Suspending originprocess definition through the repository service " + originProcessDefinitionId);
//      processEngine.getRuntimeService().s0ee98789-5d7e-11eb-bbed-00d861fc144c0ee98789-5d7e-11eb-bbed-00d861fc144cuspendProcessInstanceByProcessDefinitionId(originProcessDefinitionId);
//    }

    if (!originProcessDefinition.isSuspended()) {
      processEngine.getRepositoryService().suspendProcessDefinitionById(originProcessDefinitionId, true, null);
    }

    Boolean skipIoMappings = true; // TODO
    Boolean skipCustomListeners = true; // TODO
    String originTaskId = "Activity_0p0xl4a";
    String targetTaskId = "Activity_0p0xl4a";

    MigrationPlan migrationPlan = processEngine.getRuntimeService()
      .createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
      .mapEqualActivities()
      .updateEventTriggers()
//      .mapActivities(originTaskId, targetTaskId)
      .build();

    writer.println("Migration plan has been created");

    MigrationPlanExecutionBuilder builder = processEngine.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds("8d42e01a-60b1-11eb-924d-00d861fc144c");
    writer.println("Migration is about to be executed...");
    writer.println("\n");

    builder.execute();
    writer.println("Migration was executed");


    // Step 4. Reactivate the instances.
//    processEngine.getRuntimeService().activateProcessInstanceByProcessDefinitionId(originProcessDefinitionId);
//    processEngine.getRuntimeService().activateProcessInstanceByProcessDefinitionId(targetProcessDefinitionId);
    processEngine.getRepositoryService().activateProcessDefinitionById(originProcessDefinitionId, true, null);
    processEngine.getRepositoryService().activateProcessDefinitionById(targetProcessDefinitionId, true, null);
    writer.println("Process definitions were reactivated");

    writer.close();
    return null;


  }

  public String develop(UriInfo uriInfo, MultipartFormData multipartFormData) {
    AdaptableDeploymentService service = new AdaptableDeploymentService();
    service.deployAdaptableProcess(multipartFormData, getProcessEngine());
    throw new InvalidRequestException(Response.Status.BAD_REQUEST, "process was deployed?");
  }




  // TODO:
  // - Add the modeler to the repo and commit the progress so far.
  // - Move this into a service
  // - deploy the new process thorugh the button (because right now I hardcoded the IDs)
  // - fetch the targetProcessDefinitionID from the new deployment
  // - perform the migration on this new targetProcess

  public String develop2(UriInfo uriInfom, MultipartFormData payload) {
    PrintWriter writer = getPrintWriter();
    writer.println("Starting the adaptable process...");

    if (payload == null) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "No Mapping could be found in the Request");
    }

    Map<String, FormPart> formParts = payload.getFormParts();
    if (formParts.get("process-instance-id") == null) {
      writer.println("There was no Process Instance ID given");
      throw new InvalidRequestException(Status.BAD_REQUEST, "No Process Instance ID was given.");
    }

    String processInstanceToSuspend = formParts.get("process-instance-id").getTextContent();
    if (processInstanceToSuspend == null || processInstanceToSuspend.equals(" ")) {
      throw new InvalidRequestException(Status.BAD_REQUEST, "No Process Instance ID was given.");
    }

    String originProcessDefinitionId = "SimpleAdaptableProcess2:2:394ed9f9-5c06-11eb-934f-00d861fc144c";
    String targetProcessDefinitionId = "SimpleAdaptableProcess2:3:54e2548c-5c06-11eb-934f-00d861fc144c";

    ProcessDefinition originProcessDefinition = processEngine.getRepositoryService().getProcessDefinition(originProcessDefinitionId);
    if (!originProcessDefinition.isSuspended()) {
      processEngine.getRepositoryService().suspendProcessDefinitionById(originProcessDefinitionId, true, null);
    }

    MigrationPlan migrationPlan = processEngine.getRuntimeService()
      .createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
      .mapEqualActivities()
      .updateEventTriggers()
      .build();

    writer.println("Migration plan has been created");
    MigrationPlanExecutionBuilder builder = processEngine.getRuntimeService()
      .newMigration(migrationPlan)
      .processInstanceIds(processInstanceToSuspend);
    writer.println("Migration is about to be executed...");
    builder.execute();
    writer.println("Migration was executed");
    processEngine.getRepositoryService().activateProcessDefinitionById(originProcessDefinitionId, true, null);
    processEngine.getRepositoryService().activateProcessDefinitionById(targetProcessDefinitionId, true, null);
    writer.println("Process definitions were reactivated");

    writer.close();
    return null;

  }

  public String doSomething(String deploymentId) {
    // Step 1: suspend the instance that is running (explain order)
    // Step 2: deploy the new model. - already done for this test
    // Step 3: perform the migration.

    PrintWriter writer = getPrintWriter();
    writer.println("Starting the adaptable process...");

    String originProcessDefinitionId = "SimpleAdaptableProcess:1:edd284cc-5275-11eb-9b07-00d861fc144c";
    String targetProcessDefinitionId = "SimpleAdaptableProcess:2:0e783723-5276-11eb-9b07-00d861fc144c";

    ProcessDefinition originProcessDefinition = processEngine.getRepositoryService().getProcessDefinition(originProcessDefinitionId);
    if (!originProcessDefinition.isSuspended()) {
      writer.println("Suspending originProcess process definition through the runtime service " + originProcessDefinitionId);
//      processEngine.getRepositoryService().suspendProcessDefinitionById(originProcessDefinitionId);
      processEngine.getRuntimeService().suspendProcessInstanceByProcessDefinitionId(originProcessDefinitionId);
//      processEngine.getRuntimeService().suspendProcessInstanceByProcessDefinitionId(originProcessDefinitionId);
    }

//    DeploymentBuilder deploymentBuilder = extractDeploymentInformation(payload);
//    DeploymentWithDefinitions deployment = deploymentBuilder.deployWithResult();


    writer.close();
    return null;
  }

  public String suspendRepo() {
    PrintWriter writer = getPrintWriter();
    writer.println("Starting the adaptable process...");

    String originProcessDefinitionId = "SimpleAdaptableProcess:1:edd284cc-5275-11eb-9b07-00d861fc144c";
    String targetProcessDefinitionId = "SimpleAdaptableProcess:2:0e783723-5276-11eb-9b07-00d861fc144c";

    ProcessDefinition originProcessDefinition = processEngine.getRepositoryService().getProcessDefinition(originProcessDefinitionId);
    if (!originProcessDefinition.isSuspended()) {
      writer.println("Suspending originprocess definition through the repository service " + originProcessDefinitionId);
      processEngine.getRepositoryService().suspendProcessDefinitionById(originProcessDefinitionId);
//      processEngine.getRuntimeService().suspendProcessInstanceByProcessDefinitionId(originProcessDefinitionId);
//      processEngine.getRuntimeService().suspendProcessInstanceByProcessDefinitionId(originProcessDefinitionId);
    }

//    DeploymentBuilder deploymentBuilder = extractDeploymentInformation(payload);
//    DeploymentWithDefinitions deployment = deploymentBuilder.deployWithResult();


    writer.close();
    return null;
  }

  private void printDetailsAboutPayload(UriInfo uriInfo, MultipartFormData payload) {
    // THIS WORKS NOW. DONT TOUCH IT
    PrintWriter writer = getPrintWriter();
    writer.println("hello friends");
    if (payload!= null) {
      writer.println("there is some paylaod");
      Map<String, FormPart> mapping = payload.getFormParts();
      if (mapping != null) {
        writer.println("Size: " + mapping.size());
        writer.println("Process instance to suspend: " + mapping.get("process-instance-id").getTextContent());
        writer.println("Printing the form parts:");
        mapping.forEach((String key, FormPart value) -> {
          writer.println("Key: " +key);
          writer.println("Text: ");
          writer.println(value.getTextContent());
          writer.println("\n\n");
        });
      }
    } else {
      writer.println("payload null");
    }

    writer.close();
  }

}
