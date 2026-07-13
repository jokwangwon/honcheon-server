import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.io.Reader;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 레지스트리 로딩 시험 — 서버를 <b>띄우지 않고</b>, 진짜 마인크래프트 코덱으로 데이터팩 JSON 을 파싱한다.
 *
 * <p><b>레지스트리 실패 = 서버 사망.</b> 이 프로젝트의 첫째 규약이다.
 * 그런데 데이터팩 JSON 이 옳은지 아는 유일한 확실한 방법은 <b>바닐라 코덱에 먹여 보는 것</b>뿐이다.
 * 눈으로 읽어 맞다고 믿는 것은 검산이 아니다.
 *
 * <p>이 시험은 실제로 서버를 죽일 뻔한 버그를 잡았다:
 * strongholds 를 끄려고 {@code concentric_rings} 의 {@code count} 를 0 으로 놓았는데,
 * 그 코덱은 {@code Codec.intRange(1, 4095)} 였다 —
 * {@code "Value 0 outside of range [1:4095]"}. 월드를 깔았으면 서버가 안 떴을 것이다.
 *
 * <p>Paper 의 jar 은 mojang 매핑이라 {@code net.minecraft.*} 를 이름 그대로 부를 수 있다.
 * {@link VanillaRegistries#createLookup()} 이 바닐라 동적 레지스트리(바이옴·구조물·피처)를 통째로 세우므로
 * 우리 JSON 안의 {@code minecraft:savanna} 같은 참조까지 <b>실제로 해석</b>된다.
 *
 * <p>부르는 법 — 손으로 classpath 를 맞추지 마라 (1.21.4 잔재가 섞이면 엉뚱한 데서 죽는다):
 * <pre>  python3 tools/world_purity_audit.py --load-test</pre>
 */
public class PurityLoadTest {
    static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        RegistryOps<JsonElement> ops = lookup.createSerializationContext(JsonOps.INSTANCE);

        Path pack = Paths.get(args[0], "data/minecraft/worldgen");

        each(pack.resolve("structure_set"), ops, StructureSet.DIRECT_CODEC, "structure_set");
        each(pack.resolve("placed_feature"), ops, PlacedFeature.DIRECT_CODEC, "placed_feature");
        each(pack.resolve("world_preset"), ops, WorldPreset.DIRECT_CODEC, "world_preset");
        // 바이옴 — spawners 를 허용 목록으로 거른 65종. **빈 목록이 코덱을 통과하는가**가 여기서 갈린다.
        //   (strongholds 의 count: 0 이 [1:4095] 에 걸려 서버를 죽일 뻔한 그 자리다 —
        //    「빈 값은 당연히 되겠지」는 추측이다. 추측하지 말고 먹여 본다.)
        each(pack.resolve("biome"), ops, Biome.DIRECT_CODEC, "biome");

        System.out.println();
        System.out.println(fail == 0
                ? "PASS — " + ok + " 개 파일이 바닐라 코덱으로 전부 파싱됐다. 레지스트리는 로딩된다."
                : "FAIL — " + fail + " 개 파일이 코덱을 통과하지 못했다. **서버가 안 뜬다.**");
        System.exit(fail == 0 ? 0 : 1);
    }

    static <T> void each(Path dir, RegistryOps<JsonElement> ops,
                         com.mojang.serialization.Codec<T> codec, String label) throws Exception {
        if (!Files.isDirectory(dir)) { System.out.println("(없음) " + label); return; }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            JsonElement json;
            try (Reader r = Files.newBufferedReader(f)) { json = JsonParser.parseReader(r); }
            DataResult<T> res = codec.parse(ops, json);
            if (res.error().isPresent()) {
                fail++;
                System.out.println("  X  " + label + "/" + f.getFileName() + "  →  " + res.error().get().message());
            } else {
                ok++;
            }
        }
        System.out.println("  .  " + label + ": " + files.size() + " 개 검사");
    }
}
