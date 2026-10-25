package com.slayersuite.data;

import lombok.Data;

@Data
public class MasterRange
{
	private Integer min;
	private Integer max;
	private Integer extendedMin;
	private Integer extendedMax;
	private String raw;
}
