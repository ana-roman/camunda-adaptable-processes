package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;

import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class DeploymentBuilderService {

	public static PrintWriter writer;
	private ProcessEngine engine;
	private MultipartFormData multipartFormData;

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

	public DeploymentBuilderService(ProcessEngine processEngine, MultipartFormData payload) {
		engine = processEngine;
		writer = getPrintWriter();
		multipartFormData = payload;
	}

	public DeploymentBuilder extractDeploymentInformation() {
		DeploymentBuilder deploymentBuilder = engine.getRepositoryService().createDeployment();

		Set<String> partNames = multipartFormData.getPartNames();

		for (String name : partNames) {
			MultipartFormData.FormPart part = multipartFormData.getNamedPart(name);

			if (!RESERVED_KEYWORDS.contains(name)) {
				String fileName = part.getFileName();
				if (fileName != null) {
					deploymentBuilder.addInputStream(part.getFileName(), new ByteArrayInputStream(part.getBinaryContent()));
				} else {
					throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No file name found in the deployment resource described by form parameter '" + fileName + "'.");
				}
			}
		}

		MultipartFormData.FormPart deploymentName = multipartFormData.getNamedPart(DEPLOYMENT_NAME);
		if (deploymentName != null) {
			deploymentBuilder.name(deploymentName.getTextContent());
		}

		MultipartFormData.FormPart deploymentSource = multipartFormData.getNamedPart(DEPLOYMENT_SOURCE);
		if (deploymentSource != null) {
			deploymentBuilder.source(deploymentSource.getTextContent());
		}

		MultipartFormData.FormPart deploymentTenantId = multipartFormData.getNamedPart(TENANT_ID);
		if (deploymentTenantId != null) {
			deploymentBuilder.tenantId(deploymentTenantId.getTextContent());
		}

		extractDuplicateFilteringForDeployment(multipartFormData, deploymentBuilder);
		return deploymentBuilder;
	}

	private void extractDuplicateFilteringForDeployment(MultipartFormData payload, DeploymentBuilder deploymentBuilder) {
		boolean enableDuplicateFiltering = false;
		boolean deployChangedOnly = false;

		MultipartFormData.FormPart deploymentEnableDuplicateFiltering = payload.getNamedPart(ENABLE_DUPLICATE_FILTERING);
		if (deploymentEnableDuplicateFiltering != null) {
			enableDuplicateFiltering = Boolean.parseBoolean(deploymentEnableDuplicateFiltering.getTextContent());
		}

		MultipartFormData.FormPart deploymentDeployChangedOnly = payload.getNamedPart(DEPLOY_CHANGED_ONLY);
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
