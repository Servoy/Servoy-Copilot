package com.servoy.eclipse.developer.mcp.services;

public class NavigationEdge {
	private final String from;
	private final String to;
	private final String containerName;
	private final String containerType;
	private final String propertyName;
	private final String tabName;
	private final int tabIndex;
	private final String relationName;
	private final String trigger;
	private final String confidence;

	private NavigationEdge(Builder builder) {
		this.from = builder.from;
		this.to = builder.to;
		this.containerName = builder.containerName;
		this.containerType = builder.containerType;
		this.propertyName = builder.propertyName;
		this.tabName = builder.tabName;
		this.tabIndex = builder.tabIndex;
		this.relationName = builder.relationName;
		this.trigger = builder.trigger;
		this.confidence = builder.confidence;
	}

	public String getFrom() {
		return from;
	}

	public String getTo() {
		return to;
	}

	public String getContainerName() {
		return containerName;
	}

	public String getContainerType() {
		return containerType;
	}

	public String getPropertyName() {
		return propertyName;
	}

	public String getTabName() {
		return tabName;
	}

	public int getTabIndex() {
		return tabIndex;
	}

	public String getRelationName() {
		return relationName;
	}

	public String getTrigger() {
		return trigger;
	}

	public String getConfidence() {
		return confidence;
	}

	public String getCypressSelector() {
		if ("tabpanel".equals(containerType) && tabName != null) {
			return "[data-cy=\"" + from + "." + tabName + "\"]";
		}
		if ("navigator".equals(containerType)) {
			return null;
		}
		if (trigger != null && trigger.contains(".") && !trigger.contains("/")) {
			String elementName = trigger.split("\\.")[0];
			return "[data-cy=\"" + from + "." + elementName + "\"]";
		}
		if (containerName != null) {
			return "[data-cy=\"" + from + "." + containerName + "\"]";
		}
		return null;
	}

	public static class Builder {
		private String from;
		private String to;
		private String containerName;
		private String containerType;
		private String propertyName;
		private String tabName;
		private int tabIndex = -1;
		private String relationName;
		private String trigger;
		private String confidence = "static";

		public Builder from(String from) {
			this.from = from;
			return this;
		}

		public Builder to(String to) {
			this.to = to;
			return this;
		}

		public Builder containerName(String containerName) {
			this.containerName = containerName;
			return this;
		}

		public Builder containerType(String containerType) {
			this.containerType = containerType;
			return this;
		}

		public Builder propertyName(String propertyName) {
			this.propertyName = propertyName;
			return this;
		}

		public Builder tabName(String tabName) {
			this.tabName = tabName;
			return this;
		}

		public Builder tabIndex(int tabIndex) {
			this.tabIndex = tabIndex;
			return this;
		}

		public Builder relationName(String relationName) {
			this.relationName = relationName;
			return this;
		}

		public Builder trigger(String trigger) {
			this.trigger = trigger;
			return this;
		}

		public Builder confidence(String confidence) {
			this.confidence = confidence;
			return this;
		}

		public NavigationEdge build() {
			return new NavigationEdge(this);
		}
	}
}
