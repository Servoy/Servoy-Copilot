package com.servoy.eclipse.developer.mcp.services;

import org.eclipse.dltk.testing.model.ITestRunSession;

public record RunResult(ITestRunSession session, boolean finishedBeforeTimeout)
{
}
