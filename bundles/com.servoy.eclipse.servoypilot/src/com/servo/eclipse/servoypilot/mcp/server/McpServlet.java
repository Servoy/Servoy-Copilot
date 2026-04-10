package com.servo.eclipse.servoypilot.mcp.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servo.eclipse.servoypilot.mcp.server.langchain.McpServer;
import com.servoy.eclipse.servoypilot.tools.VibeCodingAssistantTools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/mcp")
public class McpServlet extends HttpServlet
{
	private static final long serialVersionUID = 1L;

	private static final String STORED_HASH = "tt08o+RQY14fwWESzRMuSbyYiL+BhxhWoLUO9HxgL2s=";

	private final ObjectMapper mapper = new ObjectMapper();
	private McpServer mcpServer;

	@Override
	public void init()
	{
		Map<ToolSpecification, ToolExecutor> tools = VibeCodingAssistantTools.getTools();
		this.mcpServer = new McpServer(tools);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
	{
		// 1. Extract the Bearer Token
		String authHeader = req.getHeader("Authorization");
		if (authHeader == null || !authHeader.startsWith("Bearer "))
		{
			resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
			return;
		}

		String receivedToken = authHeader.substring(7); // Remove "Bearer " prefix

		// 2. Validate the Token using Hash comparison
		if (!isValidToken(receivedToken))
		{
			resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Token");
			return;
		}

		// 3. Read the raw JSON-RPC from the request body as a JsonNode
		JsonNode requestJson = mapper.readTree(req.getInputStream());

		// 4. Let the MCP Engine process the logic
		// This returns a JsonNode representing the JSON-RPC response
		McpJsonRpcMessage responseJson = mcpServer.handle(requestJson);

		// 5. Send the result back to Claude
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");

		// Convert the result JsonNode back to a string for the response
		mapper.writeValue(resp.getWriter(), responseJson);
	}

	private boolean isValidToken(String token)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(Base64.getDecoder().decode(token.getBytes(StandardCharsets.UTF_8)));
			String receivedHash = Base64.getEncoder().encodeToString(hash);

			// Use MessageDigest.isEqual for a constant-time comparison 
			// This prevents "Timing Attacks"
			return MessageDigest.isEqual(
				STORED_HASH.getBytes(StandardCharsets.UTF_8),
				receivedHash.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			return false;
		}
	}
}