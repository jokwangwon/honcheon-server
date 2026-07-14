package com.honcheon.bot;

import com.honcheon.domain.FactionLedger;
import com.honcheon.domain.RegionLedger;

/** GameListener가 사용하는 업무 포트의 조합. 저장소 구현 세부사항은 포함하지 않는다. */
interface GameStore extends GameCharacterStore, HouseStore, EventStore, PoliticsStore,
        IdentityStore, WorldStore, FactionLedger, RegionLedger {
}
