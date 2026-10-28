package com.keyflow.onnxbuild;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê e ajusta as etiquetas de versão de símbolo (`DT_VERDEF` e `DT_VERNEED`) de um `.so` ELF, 32 ou 64 bits, little-endian.
 *
 * Por que isto existe (vision-capture VC-T2b, STATE.md): o `libonnxruntime4j_jni.so` do `onnxruntime-android` pede
 * `OrtGetApiBase@VERS_1.28.0`, e a `libonnxruntime.so` do AAR do sherpa-onnx (a única que o APK leva) define
 * `OrtGetApiBase@@VERS_1.28.2`. O linker do Android confere o nome da versão do símbolo: sem o ajuste o `System.loadLibrary` cai em
 * "cannot locate symbol OrtGetApiBase" (medido no emulador, 2026-10-27). A API C que a JNI usa é a de número 28 nas duas
 * (`ORT_API_VERSION`), então só a etiqueta difere. O ajuste troca o nome da versão exigida pela JNI pelo que a `.so` do sherpa define
 * (mesmo comprimento, no lugar) e refaz o hash ELF dela. Só roda no build; nada de binário de terceiro versionado.
 *
 * Funciona só pelos cabeçalhos de programa (`PT_LOAD`/`PT_DYNAMIC`), não pelas seções: continua valendo num `.so` sem tabela de seções.
 */
public final class ElfVersionTags {

    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_STRTAB = 5;
    private static final long DT_VERDEF = 0x6ffffffcL;
    private static final long DT_VERDEFNUM = 0x6ffffffdL;
    private static final long DT_VERNEED = 0x6ffffffeL;
    private static final long DT_VERNEEDNUM = 0x6fffffffL;

    /** Uma versão exigida por um `.so` de outra biblioteca ([file]). [hashOffset] é onde está o hash dela no arquivo. */
    public record Needed(String file, String version, int hashOffset, int nameOffset) {}

    private final ByteBuffer buf;
    private final boolean is64;
    private final List<long[]> loads = new ArrayList<>(); // vaddr, offset, filesz
    private final java.util.Map<Long, Long> dynamic = new java.util.HashMap<>();

    private ElfVersionTags(byte[] data) {
        buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            throw new IllegalArgumentException("não é um arquivo ELF");
        }
        if (data[5] != 1) throw new IllegalArgumentException("só ELF little-endian");
        is64 = data[4] == 2;
        long phoff = is64 ? buf.getLong(32) : buf.getInt(28) & 0xFFFFFFFFL;
        int phentsize = buf.getShort(is64 ? 54 : 42) & 0xFFFF;
        int phnum = buf.getShort(is64 ? 56 : 44) & 0xFFFF;
        long dynOffset = -1;
        long dynSize = 0;
        for (int i = 0; i < phnum; i++) {
            int o = (int) (phoff + (long) i * phentsize);
            int type = buf.getInt(o);
            long off;
            long vaddr;
            long filesz;
            if (is64) {
                off = buf.getLong(o + 8);
                vaddr = buf.getLong(o + 16);
                filesz = buf.getLong(o + 32);
            } else {
                off = buf.getInt(o + 4) & 0xFFFFFFFFL;
                vaddr = buf.getInt(o + 8) & 0xFFFFFFFFL;
                filesz = buf.getInt(o + 16) & 0xFFFFFFFFL;
            }
            if (type == PT_LOAD) loads.add(new long[] {vaddr, off, filesz});
            if (type == PT_DYNAMIC) {
                dynOffset = off;
                dynSize = filesz;
            }
        }
        if (dynOffset < 0) throw new IllegalArgumentException("ELF sem PT_DYNAMIC");
        int step = is64 ? 16 : 8;
        for (long o = dynOffset; o + step <= dynOffset + dynSize; o += step) {
            long tag = is64 ? buf.getLong((int) o) : buf.getInt((int) o);
            long val = is64 ? buf.getLong((int) o + 8) : buf.getInt((int) o + 4) & 0xFFFFFFFFL;
            if (tag == DT_NULL) break;
            dynamic.putIfAbsent(tag, val);
        }
    }

    public static ElfVersionTags parse(byte[] elf) {
        return new ElfVersionTags(elf);
    }

    private int fileOffset(long vaddr) {
        for (long[] l : loads) {
            if (vaddr >= l[0] && vaddr < l[0] + l[2]) return (int) (vaddr - l[0] + l[1]);
        }
        throw new IllegalArgumentException("endereço virtual fora dos segmentos: 0x" + Long.toHexString(vaddr));
    }

    private int stringOffset(int index) {
        Long strtab = dynamic.get(DT_STRTAB);
        if (strtab == null) throw new IllegalArgumentException("ELF sem DT_STRTAB");
        return fileOffset(strtab) + index;
    }

    private String stringAt(int offset) {
        int end = offset;
        while (buf.get(end) != 0) end++;
        byte[] bytes = new byte[end - offset];
        buf.get(offset, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /** Nomes das versões que o `.so` define (sem a entrada-base, que é o nome do próprio arquivo). Vazio sem `DT_VERDEF`. */
    public List<String> definedVersions() {
        List<String> out = new ArrayList<>();
        Long verdef = dynamic.get(DT_VERDEF);
        Long count = dynamic.get(DT_VERDEFNUM);
        if (verdef == null || count == null) return out;
        int o = fileOffset(verdef);
        for (long i = 0; i < count; i++) {
            int flags = buf.getShort(o + 2) & 0xFFFF;
            int aux = buf.getInt(o + 12);
            int next = buf.getInt(o + 16);
            String name = stringAt(stringOffset(buf.getInt(o + aux)));
            if ((flags & 1) == 0) out.add(name); // VER_FLG_BASE
            if (next == 0) break;
            o += next;
        }
        return out;
    }

    /** As versões de símbolo que este `.so` exige de outras bibliotecas. Vazio sem `DT_VERNEED`. */
    public List<Needed> neededVersions() {
        List<Needed> out = new ArrayList<>();
        Long verneed = dynamic.get(DT_VERNEED);
        Long count = dynamic.get(DT_VERNEEDNUM);
        if (verneed == null || count == null) return out;
        int o = fileOffset(verneed);
        for (long i = 0; i < count; i++) {
            int cnt = buf.getShort(o + 2) & 0xFFFF;
            String file = stringAt(stringOffset(buf.getInt(o + 4)));
            int aux = buf.getInt(o + 8);
            int next = buf.getInt(o + 12);
            int a = o + aux;
            for (int j = 0; j < cnt; j++) {
                int nameIndex = buf.getInt(a + 8);
                int nameOffset = stringOffset(nameIndex);
                out.add(new Needed(file, stringAt(nameOffset), a, nameOffset));
                int anext = buf.getInt(a + 12);
                if (anext == 0) break;
                a += anext;
            }
            if (next == 0) break;
            o += next;
        }
        return out;
    }

    /**
     * Troca, em todo `Vernaux` da biblioteca [neededFile] cujo nome começa por [prefix], o nome da versão por [newVersion]
     * (mesmo comprimento: reescreve a string no lugar) e grava o hash ELF novo. Devolve quantas entradas mudaram; 0 se já eram
     * iguais a [newVersion] (idempotente). Falha se o comprimento difere — aí a string não cabe e o ajuste não é seguro.
     */
    public int retarget(String neededFile, String prefix, String newVersion) {
        int changed = 0;
        for (Needed n : neededVersions()) {
            if (!n.file().equals(neededFile) || !n.version().startsWith(prefix) || n.version().equals(newVersion)) continue;
            if (n.version().length() != newVersion.length()) {
                throw new IllegalStateException(
                    "a versão exigida " + n.version() + " e a definida " + newVersion + " têm comprimentos diferentes: não cabe no lugar");
            }
            byte[] bytes = newVersion.getBytes(StandardCharsets.US_ASCII);
            for (int k = 0; k < bytes.length; k++) buf.put(n.nameOffset() + k, bytes[k]);
            buf.putInt(n.hashOffset(), elfHash(newVersion));
            changed++;
        }
        return changed;
    }

    /** O arquivo (com os ajustes feitos por [retarget]). */
    public byte[] bytes() {
        byte[] out = new byte[buf.capacity()];
        buf.get(0, out);
        return out;
    }

    /** Hash ELF (SysV) do nome, o que o `DT_VERDEF`/`DT_VERNEED` guardam. */
    public static int elfHash(String name) {
        int h = 0;
        for (byte c : name.getBytes(StandardCharsets.US_ASCII)) {
            h = (h << 4) + (c & 0xFF);
            int g = h & 0xF0000000;
            if (g != 0) h ^= g >>> 24;
            h &= ~g;
        }
        return h;
    }
}
