package com.servoy.eclipse.developer.mcp.actions;

import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.IAdapterFactory;

import com.servoy.eclipse.developer.mcp.services.CypressTestDiscoveryService;
import com.servoy.eclipse.model.ServoyModelFinder;
import com.servoy.eclipse.model.nature.ServoyProject;
import com.servoy.eclipse.model.util.ServoyLog;
import com.servoy.eclipse.ui.node.SimpleUserNode;
import com.servoy.eclipse.ui.node.UserNodeType;

public class CypressTestAdapterFactory implements IAdapterFactory {
	private static final Class<?>[] ADAPTERS = new Class[] { CypressFormTestTarget.class };

	private final CypressTestDiscoveryService discoveryService = new CypressTestDiscoveryService();

	@Override
	public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
		if (adapterType != CypressFormTestTarget.class) {
			return null;
		}
		if (!(adaptableObject instanceof SimpleUserNode node)) {
			return null;
		}

		ServoyProject activeProject = ServoyModelFinder.getServoyModel().getActiveProject();
		if (activeProject == null) {
			ServoyLog.logInfo("CypressTestAdapterFactory: no active project");
			return null;
		}

		UserNodeType type = node.getType();

		if (type == UserNodeType.FORM) {
			String formName = node.getName();
			boolean hasTest = formName != null && discoveryService.hasTest(formName);
			if (hasTest) {
				return adapterType.cast(new SingleFormTestTarget(formName));
			}
			ServoyLog.logInfo("CypressTestAdapterFactory: form '" + formName + "' hasTest=" + hasTest
				+ " activeProject=" + activeProject.getProject().getName());
		} else if (type == UserNodeType.SOLUTION || type == UserNodeType.SOLUTION_ITEM || type == UserNodeType.FORMS) {
			if (discoveryService.hasAnyTest()) {
				return adapterType.cast(new SolutionLevelTestTarget());
			}
		}

		return null;
	}

	@Override
	public Class<?>[] getAdapterList() {
		return ADAPTERS;
	}

	private static class SingleFormTestTarget implements CypressFormTestTarget {
		private final String formName;

		SingleFormTestTarget(String formName) {
			this.formName = formName;
		}

		@Override
		public String getFormName() {
			return formName;
		}

		@Override
		public boolean isSolutionLevel() {
			return false;
		}

		@Override
		public List<String> getTestFormNames() {
			return Collections.singletonList(formName);
		}
	}

	private class SolutionLevelTestTarget implements CypressFormTestTarget {
		@Override
		public String getFormName() {
			return null;
		}

		@Override
		public boolean isSolutionLevel() {
			return true;
		}

		@Override
		public List<String> getTestFormNames() {
			return discoveryService.discoverAllTestForms();
		}
	}
}
