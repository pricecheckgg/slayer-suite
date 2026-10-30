package com.slayersuite.data;

import lombok.Data;

/** One rare drop from a monster's wiki drop table: what, how many, the rate as the wiki shows it and as a probability per kill. */
@Data
public class DropRecord
{
	private String name;
	/** Quantity text when it is not 1: "2", "12-30", "15 (noted)". */
	private String qty;
	/** The rate as shown: "1/512", "3/124", "1/512 x3" when the table is rolled more than once. */
	private String rarity;
	/** Probability per kill, rolls included. */
	private Double p;
	/** Set when the row only applies on some tasks: konar (Brimstone keys), wilderness (Krystilia's tertiaries), catacombs (its tertiaries), superior. */
	private String where;
	/** Names of the task's monsters that drop it; filled by TaskRecord.rareDrops, not stored. */
	private transient java.util.List<String> from = new java.util.ArrayList<>();

	public double probability()
	{
		return p == null ? 0 : p;
	}

	/** Whether this row can drop for you now: Brimstone keys need Konar's task, Larran's keys Krystilia's, the Catacombs rows that place. */
	public boolean appliesTo(int masterId, String locationName)
	{
		if (where == null)
		{
			return true;
		}
		switch (where)
		{
			case "konar":
				return masterId == 8;
			case "wilderness":
				return masterId == 7;
			case "catacombs":
				return locationName != null && locationName.toLowerCase(java.util.Locale.ROOT).contains("catacombs");
			case "superior":
				return false;
			default:
				return true;
		}
	}

	/** Chance of at least one drop in n kills at rate p. */
	public static double chanceBy(double p, long n)
	{
		if (p <= 0 || n <= 0)
		{
			return 0;
		}
		if (p >= 1)
		{
			return 1;
		}
		return 1 - Math.pow(1 - p, n);
	}

	/** "1 in 512" from the probability, for rows whose rarity text is missing. */
	public String shownRarity()
	{
		if (rarity != null && !rarity.isEmpty())
		{
			return rarity;
		}
		return p == null || p <= 0 ? "" : "1/" + Math.round(1 / p);
	}
}
