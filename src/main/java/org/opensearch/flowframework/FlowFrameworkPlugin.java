/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.flowframework;

import org.opensearch.action.ActionRequest;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.flowframework.common.FlowFrameworkSettings;
import org.opensearch.flowframework.indices.FlowFrameworkIndicesHandler;
import org.opensearch.flowframework.rest.RestCreateWorkflowAction;
import org.opensearch.flowframework.rest.RestDeleteWorkflowAction;
import org.opensearch.flowframework.rest.RestDeprovisionWorkflowAction;
import org.opensearch.flowframework.rest.RestGetWorkflowAction;
import org.opensearch.flowframework.rest.RestGetWorkflowStateAction;
import org.opensearch.flowframework.rest.RestGetWorkflowStepAction;
import org.opensearch.flowframework.rest.RestProvisionWorkflowAction;
import org.opensearch.flowframework.rest.RestSearchWorkflowAction;
import org.opensearch.flowframework.rest.RestSearchWorkflowStateAction;
import org.opensearch.flowframework.transport.CreateWorkflowAction;
import org.opensearch.flowframework.transport.CreateWorkflowTransportAction;
import org.opensearch.flowframework.transport.DeleteWorkflowAction;
import org.opensearch.flowframework.transport.DeleteWorkflowTransportAction;
import org.opensearch.flowframework.transport.DeprovisionWorkflowAction;
import org.opensearch.flowframework.transport.DeprovisionWorkflowTransportAction;
import org.opensearch.flowframework.transport.GetWorkflowAction;
import org.opensearch.flowframework.transport.GetWorkflowStateAction;
import org.opensearch.flowframework.transport.GetWorkflowStateTransportAction;
import org.opensearch.flowframework.transport.GetWorkflowStepAction;
import org.opensearch.flowframework.transport.GetWorkflowStepTransportAction;
import org.opensearch.flowframework.transport.GetWorkflowTransportAction;
import org.opensearch.flowframework.transport.ProvisionWorkflowAction;
import org.opensearch.flowframework.transport.ProvisionWorkflowTransportAction;
import org.opensearch.flowframework.transport.ReprovisionWorkflowAction;
import org.opensearch.flowframework.transport.ReprovisionWorkflowTransportAction;
import org.opensearch.flowframework.transport.SearchWorkflowAction;
import org.opensearch.flowframework.transport.SearchWorkflowStateAction;
import org.opensearch.flowframework.transport.SearchWorkflowStateTransportAction;
import org.opensearch.flowframework.transport.SearchWorkflowTransportAction;
import org.opensearch.flowframework.transport.handler.SearchHandler;
import org.opensearch.flowframework.util.EncryptorUtils;
import org.opensearch.flowframework.workflow.WorkflowProcessSorter;
import org.opensearch.flowframework.workflow.WorkflowStepFactory;
import org.opensearch.indices.SystemIndexDescriptor;
import org.opensearch.ml.client.MachineLearningNodeClient;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SystemIndexPlugin;
import org.opensearch.remote.metadata.client.SdkClient;
import org.opensearch.remote.metadata.client.impl.SdkClientFactory;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.ScalingExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.opensearch.flowframework.common.CommonValue.CONFIG_INDEX;
import static org.opensearch.flowframework.common.CommonValue.DEPROVISION_WORKFLOW_THREAD_POOL;
import static org.opensearch.flowframework.common.CommonValue.FLOW_FRAMEWORK_THREAD_POOL_PREFIX;
import static org.opensearch.flowframework.common.CommonValue.GLOBAL_CONTEXT_INDEX;
import static org.opensearch.flowframework.common.CommonValue.PROVISION_WORKFLOW_THREAD_POOL;
import static org.opensearch.flowframework.common.CommonValue.TENANT_ID_FIELD;
import static org.opensearch.flowframework.common.CommonValue.WORKFLOW_STATE_INDEX;
import static org.opensearch.flowframework.common.CommonValue.WORKFLOW_THREAD_POOL;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.DEPROVISION_THREAD_POOL_SIZE;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.FILTER_BY_BACKEND_ROLES;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.FLOW_FRAMEWORK_ENABLED;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.FLOW_FRAMEWORK_MULTI_TENANCY_ENABLED;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.MAX_ACTIVE_DEPROVISIONS_PER_TENANT;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.MAX_ACTIVE_PROVISIONS_PER_TENANT;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.MAX_WORKFLOWS;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.MAX_WORKFLOW_STEPS;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.PROVISION_THREAD_POOL_SIZE;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.REMOTE_METADATA_ENDPOINT;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.REMOTE_METADATA_REGION;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.REMOTE_METADATA_SERVICE_NAME;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.REMOTE_METADATA_TYPE;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.TASK_REQUEST_RETRY_DURATION;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.WORKFLOW_REQUEST_TIMEOUT;
import static org.opensearch.flowframework.common.FlowFrameworkSettings.WORKFLOW_THREAD_POOL_SIZE;
import static org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_ENDPOINT_KEY;
import static org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_REGION_KEY;
import static org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_SERVICE_NAME_KEY;
import static org.opensearch.remote.metadata.common.CommonValue.REMOTE_METADATA_TYPE_KEY;
import static org.opensearch.remote.metadata.common.CommonValue.TENANT_AWARE_KEY;
import static org.opensearch.remote.metadata.common.CommonValue.TENANT_ID_FIELD_KEY;

/**
 * An OpenSearch plugin that enables builders to innovate AI apps on OpenSearch.
 */
public class FlowFrameworkPlugin extends Plugin implements ActionPlugin, SystemIndexPlugin {

    private FlowFrameworkSettings flowFrameworkSettings;

    /**
     * Instantiate this plugin.
     */
    public FlowFrameworkPlugin() {}

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        Settings settings = environment.settings();
        flowFrameworkSettings = new FlowFrameworkSettings(clusterService, settings);
        MachineLearningNodeClient mlClient = new MachineLearningNodeClient(client);
        SdkClient sdkClient = SdkClientFactory.createSdkClient(
            client,
            xContentRegistry,
            // Here we assume remote metadata client is only used with tenant awareness.
            // This may change in the future allowing more options for this map
            FLOW_FRAMEWORK_MULTI_TENANCY_ENABLED.get(settings)
                ? Map.ofEntries(
                    Map.entry(REMOTE_METADATA_TYPE_KEY, REMOTE_METADATA_TYPE.get(settings)),
                    Map.entry(REMOTE_METADATA_ENDPOINT_KEY, REMOTE_METADATA_ENDPOINT.get(settings)),
                    Map.entry(REMOTE_METADATA_REGION_KEY, REMOTE_METADATA_REGION.get(settings)),
                    Map.entry(REMOTE_METADATA_SERVICE_NAME_KEY, REMOTE_METADATA_SERVICE_NAME.get(settings)),
                    Map.entry(TENANT_AWARE_KEY, "true"),
                    Map.entry(TENANT_ID_FIELD_KEY, TENANT_ID_FIELD)
                )
                : Collections.emptyMap(),
            // TODO: Find a better thread pool or make one
            client.threadPool().executor(ThreadPool.Names.GENERIC)
        );
        EncryptorUtils encryptorUtils = new EncryptorUtils(clusterService, client, sdkClient, xContentRegistry);
        FlowFrameworkIndicesHandler flowFrameworkIndicesHandler = new FlowFrameworkIndicesHandler(
            client,
            sdkClient,
            clusterService,
            encryptorUtils,
            xContentRegistry
        );
        WorkflowStepFactory workflowStepFactory = new WorkflowStepFactory(
            threadPool,
            mlClient,
            flowFrameworkIndicesHandler,
            flowFrameworkSettings,
            client
        );
        WorkflowProcessSorter workflowProcessSorter = new WorkflowProcessSorter(workflowStepFactory, threadPool, flowFrameworkSettings);

        SearchHandler searchHandler = new SearchHandler(
            settings,
            clusterService,
            client,
            sdkClient,
            FlowFrameworkSettings.FILTER_BY_BACKEND_ROLES
        );

        return List.of(
            workflowStepFactory,
            workflowProcessSorter,
            encryptorUtils,
            flowFrameworkIndicesHandler,
            searchHandler,
            flowFrameworkSettings,
            sdkClient
        );
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        return List.of(
            new RestCreateWorkflowAction(flowFrameworkSettings),
            new RestDeleteWorkflowAction(flowFrameworkSettings),
            new RestProvisionWorkflowAction(flowFrameworkSettings),
            new RestDeprovisionWorkflowAction(flowFrameworkSettings),
            new RestSearchWorkflowAction(flowFrameworkSettings),
            new RestGetWorkflowStateAction(flowFrameworkSettings),
            new RestGetWorkflowAction(flowFrameworkSettings),
            new RestGetWorkflowStepAction(flowFrameworkSettings),
            new RestSearchWorkflowStateAction(flowFrameworkSettings)
        );
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return List.of(
            new ActionHandler<>(CreateWorkflowAction.INSTANCE, CreateWorkflowTransportAction.class),
            new ActionHandler<>(DeleteWorkflowAction.INSTANCE, DeleteWorkflowTransportAction.class),
            new ActionHandler<>(ProvisionWorkflowAction.INSTANCE, ProvisionWorkflowTransportAction.class),
            new ActionHandler<>(DeprovisionWorkflowAction.INSTANCE, DeprovisionWorkflowTransportAction.class),
            new ActionHandler<>(SearchWorkflowAction.INSTANCE, SearchWorkflowTransportAction.class),
            new ActionHandler<>(GetWorkflowStateAction.INSTANCE, GetWorkflowStateTransportAction.class),
            new ActionHandler<>(GetWorkflowAction.INSTANCE, GetWorkflowTransportAction.class),
            new ActionHandler<>(GetWorkflowStepAction.INSTANCE, GetWorkflowStepTransportAction.class),
            new ActionHandler<>(SearchWorkflowStateAction.INSTANCE, SearchWorkflowStateTransportAction.class),
            new ActionHandler<>(ReprovisionWorkflowAction.INSTANCE, ReprovisionWorkflowTransportAction.class)
        );
    }

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(
            FLOW_FRAMEWORK_ENABLED,
            MAX_WORKFLOWS,
            MAX_WORKFLOW_STEPS,
            WORKFLOW_REQUEST_TIMEOUT,
            TASK_REQUEST_RETRY_DURATION,
            FILTER_BY_BACKEND_ROLES,
            FLOW_FRAMEWORK_MULTI_TENANCY_ENABLED,
            WORKFLOW_THREAD_POOL_SIZE,
            PROVISION_THREAD_POOL_SIZE,
            MAX_ACTIVE_PROVISIONS_PER_TENANT,
            DEPROVISION_THREAD_POOL_SIZE,
            MAX_ACTIVE_DEPROVISIONS_PER_TENANT,
            REMOTE_METADATA_TYPE,
            REMOTE_METADATA_ENDPOINT,
            REMOTE_METADATA_REGION,
            REMOTE_METADATA_SERVICE_NAME
        );
    }

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        int maxSizeFromAllocatedProcessors = OpenSearchExecutors.allocatedProcessors(settings) - 1;
        return List.of(
            new ScalingExecutorBuilder(
                WORKFLOW_THREAD_POOL,
                1,
                Math.max(WORKFLOW_THREAD_POOL_SIZE.get(settings), maxSizeFromAllocatedProcessors),
                TimeValue.timeValueMinutes(1),
                FLOW_FRAMEWORK_THREAD_POOL_PREFIX + WORKFLOW_THREAD_POOL
            ),
            new ScalingExecutorBuilder(
                PROVISION_WORKFLOW_THREAD_POOL,
                1,
                Math.max(PROVISION_THREAD_POOL_SIZE.get(settings), maxSizeFromAllocatedProcessors),
                TimeValue.timeValueMinutes(5),
                FLOW_FRAMEWORK_THREAD_POOL_PREFIX + PROVISION_WORKFLOW_THREAD_POOL
            ),
            new ScalingExecutorBuilder(
                DEPROVISION_WORKFLOW_THREAD_POOL,
                1,
                Math.max(DEPROVISION_THREAD_POOL_SIZE.get(settings), maxSizeFromAllocatedProcessors),
                TimeValue.timeValueMinutes(1),
                FLOW_FRAMEWORK_THREAD_POOL_PREFIX + DEPROVISION_WORKFLOW_THREAD_POOL
            )
        );
    }

    @Override
    public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
        return List.of(
            new SystemIndexDescriptor(CONFIG_INDEX, "Flow Framework Config index"),
            new SystemIndexDescriptor(GLOBAL_CONTEXT_INDEX, "Flow Framework Global Context index"),
            new SystemIndexDescriptor(WORKFLOW_STATE_INDEX, "Flow Framework Workflow State index")
        );
    }

}
