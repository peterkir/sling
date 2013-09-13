/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.ide.eclipse.ui.internal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

import org.apache.sling.ide.eclipse.core.ISlingLaunchpadServer;
import org.apache.sling.ide.eclipse.core.ProjectUtil;
import org.apache.sling.ide.eclipse.core.ServerUtil;
import org.apache.sling.ide.eclipse.ui.internal.SerializationKindManager.SerializationKind;
import org.apache.sling.ide.filter.Filter;
import org.apache.sling.ide.filter.FilterLocator;
import org.apache.sling.ide.filter.FilterResult;
import org.apache.sling.ide.serialization.SerializationManager;
import org.apache.sling.ide.transport.Command;
import org.apache.sling.ide.transport.Repository;
import org.apache.sling.ide.transport.RepositoryException;
import org.apache.sling.ide.transport.ResourceProxy;
import org.apache.sling.ide.transport.Result;
import org.apache.sling.ide.util.PathUtil;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.wst.server.core.IServer;

// intentionally does not implement IRunnableWithProgress to cut dependency on JFace
public class ImportRepositoryContentAction {

    private final String repositoryPath;
    private final IServer server;
    private final IFile filterFile;
    private final IPath projectRelativePath;
    private final IProject project;
    private SerializationManager serializationManager;

    /**
     * @param repositoryPath
     * @param server
     * @param filterFile
     * @param projectRelativePath
     * @param project
     */
    public ImportRepositoryContentAction(String repositoryPath, IServer server, IFile filterFile,
            IPath projectRelativePath, IProject project, SerializationManager serializationManager) {
        this.repositoryPath = repositoryPath;
        this.server = server;
        this.filterFile = filterFile;
        this.projectRelativePath = projectRelativePath;
        this.project = project;
        this.serializationManager = serializationManager;
    }

    public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
        Repository repository = ServerUtil.getRepository(server, monitor);

        monitor.setTaskName("Loading configuration...");
        ISlingLaunchpadServer launchpad = (ISlingLaunchpadServer) server.loadAdapter(
                ISlingLaunchpadServer.class, monitor);

        int oldPublishState = launchpad.getPublishState();
        // TODO disabling publish does not work; since the publish is done async
        // Not sure if there is a simple workaround. Anyway, the only side effect is that we
        // make too many calls after the import, functionality is not affected
        if (server.canPublish().isOK() && oldPublishState != ISlingLaunchpadServer.PUBLISH_STATE_NEVER) {
            launchpad.setPublishState(ISlingLaunchpadServer.PUBLISH_STATE_NEVER, monitor);
        }

        SerializationKindManager skm;
        
        try {
            skm = new SerializationKindManager();
            skm.init(repository);
        } catch (RepositoryException e1) {
            throw new InvocationTargetException(e1);
        }

        Filter filter = null;

        FilterLocator filterLocator = Activator.getDefault().getFilterLocator();
        InputStream contents = null;

        if (filterFile != null && filterFile.exists()) {
            try {
                contents = filterFile.getContents();
            } catch (CoreException e) {
                throw new InvocationTargetException(e);
            } finally {
                if (contents != null) {
                    try {
                        contents.close();
                    } catch (IOException e) {
                        // don't care
                    }
                }
            }
        }

        try {
            filter = filterLocator.loadFilter(contents);
        } catch (IOException e) {
            throw new InvocationTargetException(e);
        }

        monitor.worked(5);

        try {

            // TODO: We should try to make this give 'nice' progress feedback (aka here's what I'm
            // processing)
            monitor.setTaskName("Importing...");
            monitor.worked(10);

            // we create the root node and assume this is a folder
            createRoot(project, projectRelativePath, repositoryPath);

            crawlChildrenAndImport(repository, filter, repositoryPath, project, projectRelativePath, skm);

            monitor.setTaskName("Import Complete");
            monitor.worked(100);
        } catch (Exception e) {
            throw new InvocationTargetException(e);
        } finally {
            if (oldPublishState != ISlingLaunchpadServer.PUBLISH_STATE_NEVER) {
                launchpad.setPublishState(oldPublishState, monitor);
            }
            monitor.done();
        }

    }

    private void createRoot(final IProject project, final IPath projectRelativePath,
            final String repositoryPath) throws CoreException {

        IPath rootImportPath = projectRelativePath.append(repositoryPath);

        for (int i = rootImportPath.segmentCount() - 1; i > 0; i--)
            createFolder(project, rootImportPath.removeLastSegments(i));
    }

    /**
     * Crawls the repository and recursively imports founds resources
     * 
     * @param repository the sling repository to import from
     * @param filter
     * @param path the current path to import from
     * @param project the project to create resources in
     * @param projectRelativePath the path, relative to the project root, where the resources should be
     *            created
     * @param skm
     * @param tracer
     * @throws JSONException
     * @throws RepositoryException
     * @throws CoreException
     * @throws IOException
     */
    // TODO: This probably should be pushed into the service layer
    private void crawlChildrenAndImport(Repository repository, Filter filter, String path,
            IProject project, IPath projectRelativePath, SerializationKindManager skm)
            throws RepositoryException, CoreException, IOException {

        File contentSyncRoot = ProjectUtil.getSyncDirectoryFullPath(project).toFile();

        System.out.println("crawlChildrenAndImport(" + repository + ", " + path + ", " + project + ", "
                + projectRelativePath + ")");

        ResourceProxy resource = executeCommand(repository.newListChildrenNodeCommand(path));
        String primaryType = (String) resource.getProperties().get(Repository.JCR_PRIMARY_TYPE);

        SerializationKind serializationKind = skm.getSerializationKind(primaryType);
        System.out.println(primaryType + " -> " + serializationKind);

        // TODO we should know all node types for which to create files and folders

        String serializationPath = serializationManager.getSerializationFilePath(path);
        switch (serializationKind) {
            case FILE: {
                importFile(repository, path, project, projectRelativePath);

                ResourceProxy resourceToSerialize = executeCommand(repository
                        .newGetNodeContentCommand(path));

                String out = serializationManager.buildSerializationData(contentSyncRoot,
                        resourceToSerialize, repository.getRepositoryInfo());
                if (out != null) {
                    IPath directoryPath = projectRelativePath.append(path + ".dir");
                    createFolder(project, directoryPath);
                    // TODO remove hardcoding of .content.xml name here
                    createFile(project, directoryPath.append(".content.xml"), out.getBytes("UTF-8"));
                }

                break;
            }
            case FOLDER:
            case METADATA_PARTIAL: {
                createFolder(project, projectRelativePath.append(path));
                ResourceProxy resourceToSerialize = executeCommand(repository
                        .newGetNodeContentCommand(path));

                String out = serializationManager.buildSerializationData(contentSyncRoot,
                        resourceToSerialize, repository.getRepositoryInfo());
                if (out != null) {
                    createFile(project, projectRelativePath.append(serializationPath),
                            out.getBytes("UTF-8"));
                }
                break;
            }

            case METADATA_FULL: {
                ResourceProxy resourceToSerialize = executeCommand(repository
                        .newGetNodeContentCommand(path));

                String out = serializationManager.buildSerializationData(contentSyncRoot,
                        resourceToSerialize, repository.getRepositoryInfo());

                if (out != null) {
                    // TODO - picking the base name based on serialization kind is not supported by the
                    // API...
                    // so for now this is one big hack to have vlt-compatible checkouts
                    IPath serializationPathPath = Path.fromPortableString(serializationPath);
                    if (!resourceToSerialize.getPath().equals("/")
                            && serializationPath.endsWith(".content.xml")) {
                        String name = PathUtil.getName(path);
                        if (name.indexOf(':') != -1) {
                            name = '_' + name.replace(':', '_');
                        }
                        name += ".xml";
                        serializationPath = serializationPath.replace(".content.xml", name);

                        // some of the logic should be reused from AbstractArtifact
                        serializationPathPath = Path.fromPortableString(serializationPath)
                                .removeFirstSegments(1);
                    }

                    createFile(project, projectRelativePath.append(serializationPathPath),
                            out.getBytes("UTF-8"));
                }
                break;
            }
        }

        System.out.println("Children: " + resource.getChildren());

        if (serializationKind == SerializationKind.METADATA_FULL) {
            return;
        }

        for (ResourceProxy child : resource.getChildren()) {

            // TODO - still needed?
            if (Repository.NT_RESOURCE.equals(child.getProperties().get(Repository.JCR_PRIMARY_TYPE))) {
                continue;
            }

            if (filter != null) {
                FilterResult filterResult = filter.filter(contentSyncRoot, child.getPath(),
                        repository.getRepositoryInfo());
                if (filterResult == FilterResult.DENY) {
                    continue;
                }
            }

            crawlChildrenAndImport(repository, filter, child.getPath(), project, projectRelativePath, skm);
        }
    }

    private <T> T executeCommand(Command<T> command) throws RepositoryException {

        Result<T> result = command.execute();
        return result.get();
    }

    private void importFile(Repository repository, String path, IProject project, IPath destinationPath)
            throws RepositoryException, CoreException {

        System.out.println("importFile: " + path + " -> " + destinationPath);

        byte[] node = executeCommand(repository.newGetNodeCommand(path));
        createFile(project, destinationPath.append(path), node);
    }

    private void createFolder(IProject project, IPath destinationPath) throws CoreException {

        IFolder destinationFolder = project.getFolder(destinationPath);
        if (destinationFolder.exists())
            return;

        destinationFolder.create(true, true, null /* TODO progress monitor */);
    }

    private void createFile(IProject project, IPath path, byte[] node) throws CoreException {

        IFile destinationFile = project.getFile(path);

        System.out.println("Writing content file at " + path);

        if (destinationFile.exists()) {
            /* TODO progress monitor */
            destinationFile.setContents(new ByteArrayInputStream(node), IResource.KEEP_HISTORY, null);
        } else {
            /* TODO progress monitor */
            destinationFile.create(new ByteArrayInputStream(node), true, null);
        }
    }
}