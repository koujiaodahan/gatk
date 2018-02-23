package org.broadinstitute.hellbender.tools.spark.sv.discovery.prototype;

import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.spark.sv.evidence.FindBreakpointEvidenceSpark;
import org.broadinstitute.hellbender.tools.spark.sv.utils.*;
import org.broadinstitute.hellbender.tools.spark.sv.utils.SVFastqUtils.FastqRead;
import org.broadinstitute.hellbender.tools.spark.utils.HopscotchMultiMap;
import org.broadinstitute.hellbender.utils.BaseUtils;
import org.broadinstitute.hellbender.utils.fermi.FermiLiteAssembler;
import org.broadinstitute.hellbender.utils.fermi.FermiLiteAssembly;
import org.broadinstitute.hellbender.utils.fermi.FermiLiteAssembly.Contig;

import java.util.*;
import java.util.stream.IntStream;

@BetaFeature
@CommandLineProgramProperties(
        oneLineSummary = "(Internal) total junk", summary = "a complete mess",
        programGroup = StructuralVariantDiscoveryProgramGroup.class)
public class AssembleAndAlign extends CommandLineProgram {
    private static final long serialVersionUID = 1L;

    @Argument(doc = "input fastq",
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME)
    private String fastqFile;

    @Override
    protected Object doWork() {
        final int kmerSize = 31;
        final int minQ = 10;
        final int maxValidQualSum = 60;

        // read the reads
        final List<FastqRead> reads = SVFastqUtils.readFastqFile(fastqFile);

        // assemble them
        final FermiLiteAssembler assembler = new FermiLiteAssembler();
        System.out.println("Cleaning flag: " + assembler.getCleaningFlag());
        assembler.setCleaningFlag((0x20 | assembler.getCleaningFlag()) & ~0x80);
        System.out.println("Adjusted cleaning flag: " + assembler.getCleaningFlag());
        final FermiLiteAssembly assembly = assembler.createAssembly(reads);

        // make a map of assembled kmers
        final int capacity = assembly.getContigs().stream().mapToInt(tig -> tig.getSequence().length - kmerSize + 1).sum();
        final HopscotchMultiMap<SVKmerShort, ContigLocation, KmerLocation> kmerMap = new HopscotchMultiMap<>(capacity);
        assembly.getContigs().forEach(tig -> {
            int contigOffset = 0;
            final Iterator<SVKmer> contigItr = new SVKmerizer(tig.getSequence(), kmerSize, new SVKmerShort());
            while ( contigItr.hasNext() ) {
                final SVKmerShort kmer = (SVKmerShort)contigItr.next();
                final SVKmerShort canonicalKmer = kmer.canonical(kmerSize);
                final ContigLocation location = new ContigLocation(tig, contigOffset++, kmer.equals(canonicalKmer));
                kmerMap.add(new KmerLocation(canonicalKmer, location));
            }
        });

        // tile each read with a contig path
        final Map<Contig, Integer> contigIdMap = new HashMap<>(SVUtils.hashMapCapacity(assembly.getNContigs()));
        for ( int id = 0; id != assembly.getNContigs(); ++id ) {
            contigIdMap.put(assembly.getContigs().get(id), id);
        }

        final int nReads = reads.size();
        if ( (nReads & 1) != 0 ) {
            throw new UserException("FASTQ input file must have interleaved pairs -- but this file has an odd number of reads.");
        }
        for ( int idx = 0; idx < nReads; idx += 2 ) {
            if ( !Objects.equals(reads.get(idx).getName(), reads.get(idx+1).getName()) ) {
                throw new UserException("FASTQ input file must have interleaved pairs -- but records " + idx +
                                        " and " + (idx+1) + " have different names");
            }
            final StringBuilder sb = new StringBuilder();
            String sep = "";
            for ( final ReadSpan span :
                    findSpans(reads.get(idx), kmerSize, minQ, maxValidQualSum, kmerMap, contigIdMap).keySet() ) {
                sb.append(sep).append(span);
                sep = "; ";
            }
            sb.append(" | ");
            sep = "";
            for (final ReadSpan span :
                    findSpans(reads.get(idx+1), kmerSize, minQ, maxValidQualSum, kmerMap, contigIdMap).keySet() ) {
                sb.append(sep).append(span);
                sep = "; ";
            }
            System.out.println(sb);
        }
        return null;
    }

    private static byte[] trimmedRead( final FastqRead read, final int minQ ) {
        final byte[] quals = read.getQuals();
        final int trimLen =
                IntStream.range(0, quals.length).filter(idx -> quals[idx] < minQ).findFirst().orElse(quals.length);
        return Arrays.copyOf(read.getBases(), trimLen);
    }

    private static SortedMap<ReadSpan, FindBreakpointEvidenceSpark.IntPair> findSpans(
            final FastqRead read,
            final int kmerSize,
            final int minQ,
            final int maxValidQualSum,
            final HopscotchMultiMap<SVKmerShort, ContigLocation, KmerLocation> kmerMap,
            final Map<Contig, Integer> contigIdMap ) {
        final SortedMap<ReadSpan, FindBreakpointEvidenceSpark.IntPair> spanMap = new TreeMap<>();
        final byte[] readBases = trimmedRead(read, minQ);
        final byte[] readQuals = read.getQuals();
        int readOffset = 0;
        final Iterator<SVKmer> readItr = new SVKmerizer(readBases, kmerSize, new SVKmerShort());
        while ( readItr.hasNext() ) {
            final SVKmerShort readKmer = (SVKmerShort)readItr.next();
            final SVKmerShort canonicalReadKmer = readKmer.canonical(kmerSize);
            final boolean canonical = readKmer.equals(canonicalReadKmer);
            final Iterator<KmerLocation> locItr = kmerMap.findEach(canonicalReadKmer);
            while ( locItr.hasNext() ) {
                final ContigLocation location = locItr.next().getLocation();
                final byte[] contigBases = location.getContig().getSequence();
                final boolean isRC = canonical != location.isCanonical();
                final int contigOffset =
                        isRC ? contigBases.length - location.getOffset() - kmerSize : location.getOffset();
                final int leftSpan = Math.min(readOffset, contigOffset);
                final int readStart = readOffset - leftSpan;
                final int contigStart = contigOffset - leftSpan;
                final int length =
                        leftSpan + Math.min(readBases.length - readOffset, contigBases.length - contigOffset);
                final ReadSpan span =
                        new ReadSpan(readStart, isRC ? (contigBases.length - contigStart - length) : contigStart,
                                        length, readBases.length, contigBases.length,
                                        contigIdMap.get(location.getContig()), isRC);
                if ( spanMap.containsKey(span) ) continue;
                if ( !isRC ) {
                    int nMismatches = 0;
                    int qualSum = 0;
                    for ( int idx = 0; idx != length; ++idx ) {
                        if ( readBases[readStart+idx] != contigBases[contigStart+idx] ) {
                            nMismatches += 1;
                            qualSum += readQuals[readStart+idx];
                        }
                    }
                    spanMap.put(span, new FindBreakpointEvidenceSpark.IntPair(nMismatches, qualSum));
                } else {
                    int nMismatches = 0;
                    int qualSum = 0;
                    final int contigRCOffset = contigBases.length - contigStart - 1;
                    for ( int idx = 0; idx != length; ++idx ) {
                        if ( readBases[readStart+idx] != BaseUtils.simpleComplement(contigBases[contigRCOffset-idx]) ) {
                            nMismatches += 1;
                            qualSum += readQuals[readStart+idx];
                        }
                    }
                    spanMap.put(span, new FindBreakpointEvidenceSpark.IntPair(nMismatches, qualSum));
                }
            }
            readOffset += 1;
        }
        final Iterator<FindBreakpointEvidenceSpark.IntPair> itr = spanMap.values().iterator();
        while ( itr.hasNext() ) {
            if ( itr.next().int2() > maxValidQualSum ) itr.remove();
        }
        return spanMap;
    }

    private static final class ReadSpan implements Comparable<ReadSpan> {
        private final int readStart;
        private final int contigStart;
        private final int length;
        private final int readLen;
        private final int contigLen;
        private final int contigId;
        private final boolean isRC;

        public ReadSpan( final int readStart, final int contigStart, final int length,
                         final int readLen, final int contigLen, final int contigId, final boolean isRC ) {
            this.readStart = readStart;
            this.contigStart = contigStart;
            this.length = length;
            this.readLen = readLen;
            this.contigLen = contigLen;
            this.contigId = contigId;
            this.isRC = isRC;
        }

        @Override public boolean equals( final Object obj ) {
            if ( this == obj ) return true;
            return obj instanceof ReadSpan && equals((ReadSpan)obj);
        }

        public boolean equals( final ReadSpan that ) {
            return readStart == that.readStart &&
                    contigStart == that.contigStart &&
                    length == that.length &&
                    contigId == that.contigId &&
                    isRC == that.isRC;
        }

        @Override public int hashCode() {
            int hashCode = 23;
            hashCode = (47 * hashCode) + readStart;
            hashCode = (47 * hashCode) + contigStart;
            hashCode = (47 * hashCode) + length;
            hashCode = (47 * hashCode) + contigId;
            hashCode = (47 * hashCode) + (isRC ? 17 : 7);
            return hashCode;
        }

        @Override public int compareTo( final ReadSpan that ) {
            int result = Integer.compare(readStart, that.readStart);
            if ( result == 0 ) result = Integer.compare(length, that.length);
            return result;
        }

        @Override public String toString() {
            return readStart + "-" + (readStart + length) + "/" + readLen + " -> " +
                    (isRC ? "-" : "+") + contigId + ":" + contigStart + "-" + (contigStart + length) + "/" + contigLen;
        }
    }

    private static final class ContigLocation {
        private final Contig contig;
        private final int offset;
        private final boolean canonical;
        public ContigLocation( final Contig contig, final int offset, final boolean canonical ) {
            this.contig = contig;
            this.offset = offset;
            this.canonical = canonical;
        }
        public Contig getContig() { return contig; }
        public int getOffset() { return offset; }
        public boolean isCanonical() { return canonical; }
    }

    private static final class KmerLocation implements Map.Entry<SVKmerShort, ContigLocation> {
        private final SVKmerShort kmer;
        private final ContigLocation location;

        public KmerLocation( final SVKmerShort kmer, final ContigLocation location ) {
            this.kmer = kmer;
            this.location = location;
        }

        public SVKmerShort getKmer() { return kmer; }
        public ContigLocation getLocation() { return location; }
        @Override public SVKmerShort getKey() { return kmer; }
        @Override public ContigLocation getValue() { return location; }
        @Override public ContigLocation setValue( final ContigLocation value ) {
            throw new UnsupportedOperationException("KmerLocation is immutable");
        }
    }
}
