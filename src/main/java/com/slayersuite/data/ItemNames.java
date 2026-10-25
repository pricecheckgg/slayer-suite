package com.slayersuite.data;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Name normalisation shared by the item groups file and the readiness scan.
 * "Black mask (10) (i)" and "Black mask" both become "black mask";
 * "Fungicide spray 10" becomes "fungicide spray"; "Dragonfire shield (uncharged)" keeps its suffix.
 */
public final class ItemNames
{
	private static final Pattern SUFFIX = Pattern.compile("\\s*\\((\\d+|[a-z]{1,2}|p\\+{1,2}|or|nz|deg|unf)\\)");
	private static final Pattern TRAILING_NUMBER = Pattern.compile("\\s+\\d+$");
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

	private ItemNames()
	{
	}

	public static String normalize(String name)
	{
		if (name == null)
		{
			return "";
		}
		String s = name.toLowerCase(Locale.ROOT);
		s = SUFFIX.matcher(s).replaceAll("");
		s = TRAILING_NUMBER.matcher(s).replaceAll("");
		return s.replaceAll("\\s+", " ").trim();
	}

	/** Loose key for task names: lower case letters and digits only. */
	public static String key(String name)
	{
		if (name == null)
		{
			return "";
		}
		return NON_ALNUM.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
	}
}
