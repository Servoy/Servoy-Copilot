/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 */
package com.servoy.eclipse.servoypilot.tools;

import java.net.URI;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

/**
 * Describes a resource that can be cached. Provides factory methods
 * for creating descriptors from Eclipse resource types.
 * 
 * URI Schemes:
 * - workspace:///ProjectName/path/to/file.java  â Workspace files (IFile)
 * - project:///ProjectName/layout               â Project structure
 * - console:///ConsoleName                      â Console output
 */
public record ResourceDescriptor(
    URI uri,
    ResourceType type,
    String displayName,
    IPath workspacePath,    // null for non-workspace resources
    String toolName
) {
    
    public enum ResourceType {
        WORKSPACE_FILE,     // IFile in workspace
        JAVA_TYPE,          // IType (class/interface)
        PROJECT_LAYOUT,     // Project structure
        CONSOLE_OUTPUT,     // Console content
        EXTERNAL_FILE,      // File outside workspace
        QUERY_RESULT,       // Database/search result
        TRANSIENT           // Non-cacheable
    }
    
    /**
     * Creates descriptor from an Eclipse workspace file.
     */
    public static ResourceDescriptor fromWorkspaceFile(IFile file, String toolName) {
        IPath fullPath = file.getFullPath();
        URI uri = createWorkspaceUri(fullPath);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.WORKSPACE_FILE,
            file.getName(),
            fullPath,
            toolName
        );
    }
    
    /**
     * Creates descriptor from an Eclipse IResource.
     */
    public static ResourceDescriptor fromResource(IResource resource, String toolName) {
        if (resource instanceof IFile file) {
            return fromWorkspaceFile(file, toolName);
        }
        
        IPath fullPath = resource.getFullPath();
        URI uri = createWorkspaceUri(fullPath);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.WORKSPACE_FILE,
            resource.getName(),
            fullPath,
            toolName
        );
    }

    
    /**
     * Creates descriptor for project layout.
     */
    public static ResourceDescriptor forProjectLayout(String projectName, String toolName) {
        URI uri = URI.create("project:///" + encode(projectName) + "/layout");
        IPath workspacePath = IPath.fromOSString("/" + projectName);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.PROJECT_LAYOUT,
            projectName + " (layout)",
            workspacePath,
            toolName
        );
    }
    
    /**
     * Creates descriptor for console output.
     */
    public static ResourceDescriptor forConsole(String consoleName, String toolName) {
        URI uri = URI.create("console:///" + encode(consoleName));
        
        return new ResourceDescriptor(
            uri,
            ResourceType.CONSOLE_OUTPUT,
            consoleName,
            null,
            toolName
        );
    }
    
    /**
     * Creates descriptor for transient (non-cacheable) results.
     */
    public static ResourceDescriptor transientResult(String toolName) {
        return new ResourceDescriptor(
            null,
            ResourceType.TRANSIENT,
            "transient",
            null,
            toolName
        );
    }
    
    /**
     * Checks if this resource exists in the workspace.
     */
    public boolean existsInWorkspace() {
        if (workspacePath == null) {
            return false;
        }
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(workspacePath);
        return resource != null && resource.exists();
    }
    
    /**
     * Gets the IFile if this is a workspace file resource.
     */
    public Optional<IFile> toWorkspaceFile() {
        if (workspacePath == null) {
            return Optional.empty();
        }
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(workspacePath);
        return resource instanceof IFile file ? Optional.of(file) : Optional.empty();
    }
    
    /**
     * Returns true if this resource should be cached.
     */
    public boolean isCacheable() {
        return type != ResourceType.TRANSIENT && uri != null;
    }
    
    // --- Helper methods ---
    
    private static URI createWorkspaceUri(IPath path) {
        return URI.create("workspace://" + path.toString());
    }
    
    private static String encode(String value) {
        return value.replace(" ", "%20")
                    .replace("#", "%23")
                    .replace("?", "%3F");
    }
}
