package com.slayersuite;

import com.slayersuite.history.TaskHistory;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WeekMathTest
{
	@Test
	public void percentChangeAgainstLastWeek()
	{
		assertEquals("+12%", TaskHistory.percentChange(112, 100));
		assertEquals("-8%", TaskHistory.percentChange(92, 100));
		assertEquals("+0%", TaskHistory.percentChange(100, 100));
		assertEquals("new", TaskHistory.percentChange(5, 0));
		assertEquals("--", TaskHistory.percentChange(0, 0));
		assertEquals("-100%", TaskHistory.percentChange(0, 10));
	}
}
