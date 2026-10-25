package com.slayersuite.data;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** One readiness rule from the dataset: any one of the item groups satisfies it. */
@Data
public class ItemRule
{
	private List<String> anyOf = new ArrayList<>();
	private String why;
	/** "required" or "recommended". */
	private String level = "required";
	/** For finishing items: the Hitpoints at or below which the item finishes the kill. */
	private Integer threshold;

	public boolean isRequired()
	{
		return !"recommended".equalsIgnoreCase(level);
	}
}
