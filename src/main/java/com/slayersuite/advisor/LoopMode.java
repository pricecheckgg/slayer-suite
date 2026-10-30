package com.slayersuite.advisor;

/** What the route does once an assignment ends. */
public enum LoopMode
{
	OFF("Off"),
	MASTER("Back to the master"),
	BANK_THEN_MASTER("Bank when short, then the master");

	private final String label;

	LoopMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
