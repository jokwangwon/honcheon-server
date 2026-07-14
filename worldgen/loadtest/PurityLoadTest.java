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

        HolderLookup.Provider lookup = tagTolerant(VanillaRegistries.createLookup());
        RegistryOps<JsonElement> ops = lookup.createSerializationContext(JsonOps.INSTANCE);

        // 인자마다 데이터팩 하나 — honcheon_purity 와 형제 honcheon_no_caves 를 **같은 코덱**에 먹인다.
        for (String root : args) {
            Path pack = Paths.get(root, "data/minecraft/worldgen");
            System.out.println("── " + Paths.get(root).getFileName());

            each(pack.resolve("structure_set"), ops, StructureSet.DIRECT_CODEC, "structure_set");
            each(pack.resolve("placed_feature"), ops, PlacedFeature.DIRECT_CODEC, "placed_feature");
            each(pack.resolve("world_preset"), ops, WorldPreset.DIRECT_CODEC, "world_preset");
            // 바이옴 — spawners 를 허용 목록으로 거른 65종. **빈 목록이 코덱을 통과하는가**가 여기서 갈린다.
            //   (strongholds 의 count: 0 이 [1:4095] 에 걸려 서버를 죽일 뻔한 그 자리다 —
            //    「빈 값은 당연히 되겠지」는 추측이다. 추측하지 말고 먹여 본다.)
            each(pack.resolve("biome"), ops, Biome.DIRECT_CODEC, "biome");

            // no_caves 의 세 갈래 (B-113) — 카버·밀도함수·노이즈.
            //   density_function 은 중첩 폴더(overworld/caves/…)라 each() 가 재귀로 걷는다.
            //   noise 의 amplitudes 전부 0.0 — 「0 은 당연히 되겠지」도 추측이다. 먹여 본다.
            each(pack.resolve("configured_carver"), ops,
                    net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver.DIRECT_CODEC,
                    "configured_carver");
            each(pack.resolve("density_function"), ops,
                    net.minecraft.world.level.levelgen.DensityFunction.DIRECT_CODEC,
                    "density_function");
            each(pack.resolve("noise"), ops,
                    net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC,
                    "noise");
        }

        System.out.println();
        System.out.println(fail == 0
                ? "PASS — " + ok + " 개 파일이 바닐라 코덱으로 전부 파싱됐다. 레지스트리는 로딩된다."
                : "FAIL — " + fail + " 개 파일이 코덱을 통과하지 못했다. **서버가 안 뜬다.**");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * 태그를 <b>전방 참조</b>로 받아 주는 조회 — 실서버의 레지스트리 로딩과 같은 관용이다.
     *
     * <p>실서버는 worldgen 레지스트리를 파싱할 때 태그를 아직 안 묶었다 — 모르는 태그는
     * 빈 자리로 만들어 두고 나중에 채운다(전방 참조). 그런데 {@link VanillaRegistries#createLookup()}
     * 은 태그가 하나도 안 묶인 조회라, 바닐라 <b>자기 자신의</b> 카버 파일
     * ({@code replaceable: "#minecraft:overworld_carver_replaceables"})조차 퇴짜를 놓는다.
     * 그 어긋남 때문에 첫 판의 no_caves 카버가 {@code replaceable} 까지 갈아 끼웠다 —
     * <b>시험기의 구멍이 정본 편집을 왜곡한 것이다.</b> 여기서 구멍을 막는다:
     * 없는 태그는 실서버처럼 빈 이름표로 받아 준다.
     * (이 시험이 태그의 <b>실재</b>까지 보증하지는 않는다 — 우리가 쓰는 태그는 바닐라 자신의
     * 것뿐이라, 실재는 바닐라가 보증한다.)
     */
    static HolderLookup.Provider tagTolerant(HolderLookup.Provider base) {
        return new HolderLookup.Provider() {
            @Override
            public Stream<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>> listRegistryKeys() {
                return base.listRegistryKeys();
            }

            @Override
            public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends T>> key) {
                return base.lookup(key).map(l -> new HolderLookup.RegistryLookup.Delegate<T>() {
                    @Override
                    public HolderLookup.RegistryLookup<T> parent() {
                        return l;
                    }

                    @Override
                    public Optional<net.minecraft.core.HolderSet.Named<T>> get(net.minecraft.tags.TagKey<T> tag) {
                        return l.get(tag).or(() -> Optional.of(net.minecraft.core.HolderSet.emptyNamed(l, tag)));
                    }
                });
            }
        };
    }

    static <T> void each(Path dir, RegistryOps<JsonElement> ops,
                         com.mojang.serialization.Codec<T> codec, String label) throws Exception {
        if (!Files.isDirectory(dir)) { System.out.println("  (없음) " + label); return; }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {          // 재귀 — density_function 은 중첩 폴더다
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            JsonElement json;
            try (Reader r = Files.newBufferedReader(f)) { json = JsonParser.parseReader(r); }
            DataResult<T> res = codec.parse(ops, json);
            if (res.error().isPresent()) {
                fail++;
                System.out.println("  X  " + label + "/" + dir.relativize(f) + "  →  " + res.error().get().message());
            } else {
                ok++;
            }
        }
        System.out.println("  .  " + label + ": " + files.size() + " 개 검사");
    }
}
