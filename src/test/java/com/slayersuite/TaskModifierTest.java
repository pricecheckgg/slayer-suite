package com.slayersuite;

import com.slayersuite.task.TaskModifier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TaskModifierTest
{
	@Test
	public void idsFollowTheGamesTable()
	{
		assertEquals(TaskModifier.Kind.NONE, TaskModifier.kindOf(0));
		assertEquals(TaskModifier.Kind.POINTS, TaskModifier.kindOf(1));
		assertEquals(TaskModifier.Kind.QUANTITY, TaskModifier.kindOf(2));
		assertEquals(TaskModifier.Kind.CLUES, TaskModifier.kindOf(3));
		assertEquals(TaskModifier.Kind.SUPERIOR_CHANCE, TaskModifier.kindOf(4));
		assertEquals(TaskModifier.Kind.XP_BOOST, TaskModifier.kindOf(5));
		assertEquals(TaskModifier.Kind.UNKNOWN, TaskModifier.kindOf(9));
	}

	@Test
	public void labelsReadLikeTheTaskChoiceScreen()
	{
		assertEquals("+265% Superior unique chance", TaskModifier.of(TaskModifier.Kind.SUPERIOR_CHANCE, 265, false).label());
		assertEquals("+75 Assigned", TaskModifier.of(TaskModifier.Kind.QUANTITY, 75, false).label());
		assertEquals("-20 Assigned", TaskModifier.of(TaskModifier.Kind.QUANTITY, 20, true).label());
		assertEquals("+40 Slayer Points", TaskModifier.of(TaskModifier.Kind.POINTS, 40, false).label());
		assertEquals("+150% Clue chance", TaskModifier.of(TaskModifier.Kind.CLUES, 150, false).label());
		assertEquals("+30% Slayer XP", TaskModifier.of(TaskModifier.Kind.XP_BOOST, 30, false).label());
		assertEquals("+7 bonus", TaskModifier.of(TaskModifier.Kind.UNKNOWN, 7, false).label());
	}

	@Test
	public void noneIsOneValueWithNoLabel()
	{
		assertSame(TaskModifier.NONE, TaskModifier.of(TaskModifier.Kind.NONE, 5, false));
		assertSame(TaskModifier.NONE, TaskModifier.of(null, 5, false));
		assertEquals("", TaskModifier.NONE.label());
		assertFalse(TaskModifier.NONE.present());
		assertTrue(TaskModifier.of(TaskModifier.Kind.POINTS, 10, false).present());
	}

	@Test
	public void onlyPercentagesScale()
	{
		assertEquals(3.65, TaskModifier.of(TaskModifier.Kind.SUPERIOR_CHANCE, 265, false).factor(), 1e-9);
		assertEquals(1.3, TaskModifier.of(TaskModifier.Kind.XP_BOOST, 30, false).factor(), 1e-9);
		assertEquals(1.0, TaskModifier.of(TaskModifier.Kind.POINTS, 40, false).factor(), 0.0);
		assertEquals(1.0, TaskModifier.of(TaskModifier.Kind.QUANTITY, 75, false).factor(), 0.0);
		// the sign comes from the game's flag; the size is always read as a magnitude
		assertEquals(-20, TaskModifier.of(TaskModifier.Kind.QUANTITY, 20, true).getValue());
		assertEquals(20, TaskModifier.of(TaskModifier.Kind.QUANTITY, -20, false).getValue());
	}
}
