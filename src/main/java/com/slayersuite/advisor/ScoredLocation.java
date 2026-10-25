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
}
