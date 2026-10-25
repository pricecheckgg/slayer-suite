package com.slayersuite.task;

public enum SlayerModeSetting
{
	AUTO("Auto"),
	ON("Always on"),
	OFF("Always off");

	private final String label;

	SlayerModeSetting(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
