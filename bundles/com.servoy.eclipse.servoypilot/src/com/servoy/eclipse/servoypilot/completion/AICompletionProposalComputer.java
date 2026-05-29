package com.servoy.eclipse.servoypilot.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.dltk.ui.text.completion.ContentAssistInvocationContext;
import org.eclipse.dltk.ui.text.completion.IScriptCompletionProposalComputer;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.templates.DocumentTemplateContext;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.jface.text.templates.TemplateContextType;
import org.eclipse.jface.text.templates.TemplateProposal;
import org.json.JSONArray;

import com.servoy.eclipse.servoypilot.Activator;
import com.servoy.eclipse.servoypilot.ai.CompletionAssistent;

public class AICompletionProposalComputer implements IScriptCompletionProposalComputer
{
	final List<ICompletionProposal> proposals = new ArrayList<>();

	public AICompletionProposalComputer()
	{
	}

	@Override
	public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext context, IProgressMonitor monitor)
	{
		try
		{
			// 1. Get Context
			IDocument document = context.getDocument();
			int offset = context.getInvocationOffset();

			// Get the last 1000 characters for context (don't send the whole file if it's huge)
			int start = Math.max(0, offset - 1000);
			String prefix = document.get(start, offset - start);

			Job.create(prefix, mon -> {

				// 3. Call LangChain4j (WARNING: Blocking call on UI thread!)
				// Ideally, you should check 'monitor.isCanceled()' frequently
				if (mon != null)
				{
					mon.beginTask("Requesting AI suggestions...", IProgressMonitor.UNKNOWN);
				}

				CompletionAssistent model = Activator.getDefault().getCompletionAssistant();
				String aiResponse = model.complete(prefix);

				// 4. Clean up response (Remove markdown code blocks if AI added them)
				aiResponse = aiResponse.replaceAll("```javascript", "").replaceAll("```", "").trim();

				JSONArray suggestionsArray = new JSONArray(aiResponse);
				suggestionsArray.forEach(item -> {

					String suggestion = item.toString();
					// 5. Create a TemplateProposal
					// We convert the AI response into a Template so the user can Tab through it
					// Example: "function(a, b)" -> "function(${a}, ${b})" logic could go here
					Template aiTemplate = new Template(
						suggestion, // Name in popup
						"", // Description
						"javascript-context", // Context Type ID (matches your plugin.xml)
						suggestion, // The pattern/code
						true // Auto-insertable
					);

					// Create the context for the template
					TemplateContextType contextType = new TemplateContextType("javascript-context");
					DocumentTemplateContext templateContext = new DocumentTemplateContext(
						contextType, document, offset, 0);

					// 6. Build the Proposal
					ICompletionProposal proposal = new TemplateProposal(
						aiTemplate,
						templateContext,
						new Region(offset, 0),
						null // You can load an icon here using AbstractUIPlugin.imageDescriptorFromPlugin(...)
					);

					proposals.add(proposal);
				});
				if (mon != null)
				{
					mon.done();
				}
			}).schedule();
		}
		catch (Exception e)
		{
			e.printStackTrace(); // Log to Error View in real app
		}
		return proposals;
	}

	@Override
	public List<IContextInformation> computeContextInformation(ContentAssistInvocationContext context, IProgressMonitor monitor)
	{
		return Collections.emptyList();
	}

	@Override
	public String getErrorMessage()
	{
		return null;
	}

	@Override
	public void sessionStarted()
	{
		System.err.println("Session started");
	}

	@Override
	public void sessionEnded()
	{
		System.err.println("Session ended");
		proposals.clear();
	}

}
