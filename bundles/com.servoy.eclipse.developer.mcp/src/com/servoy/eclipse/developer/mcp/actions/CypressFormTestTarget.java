package com.servoy.eclipse.developer.mcp.actions;

import java.util.List;

public interface CypressFormTestTarget {
	String getFormName();

	boolean isSolutionLevel();

	List<String> getTestFormNames();
}
