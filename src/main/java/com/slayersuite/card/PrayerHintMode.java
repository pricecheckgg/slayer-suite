package com.slayersuite.card;

public enum PrayerHintMode
{
	OFF("Off"),
	WHEN_OFF("While that prayer is off"),
	ALWAYS("Always");

	private final String label;

	PrayerHintMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
