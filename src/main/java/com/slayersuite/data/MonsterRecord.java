package com.slayersuite.data;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class MonsterRecord
{
	private String name;
	private String label;
	private String wikiPage;
	private List<Integer> npcIds = new ArrayList<>();
	private Integer combat;
	private Integer combatMax;
	private Double slayerXp;
	private Double slayerXpMax;
	private Integer hitpoints;
	private Integer hitpointsMax;
	private String maxHit;
	private List<String> attackStyles = new ArrayList<>();
	private List<String> attributes = new ArrayList<>();
	private boolean poisonous;
	private boolean venomous;
	private boolean cannonImmune;
	private String elementalWeakness;
	private String category;
	private boolean superior;
	private List<String> locations = new ArrayList<>();
	private List<String> notes = new ArrayList<>();
}
