package com.slayersuite.advisor;

import com.slayersuite.data.LocationRecord;
import java.util.List;
import lombok.Value;

@Value
public class ScoredLocation
{
	LocationRecord location;
	int score;
	boolean locked;
	boolean wilderness;
	String travel;
	List<String> reasons;
	/** Your own rate at this spot, or null before you have played enough there. */
	String ownRate;
	/** Seconds from where you stood when ranked, by the router when it has answered, else a straight-line guess; -1 unknown. */
	int travelSeconds;
}
