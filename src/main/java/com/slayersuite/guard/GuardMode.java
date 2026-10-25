package com.slayersuite.guard;

public enum GuardMode
{
	DEPRIORITIZE("Deprioritise"),
	HIDE("Hide");

	private final String label;

	GuardMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
