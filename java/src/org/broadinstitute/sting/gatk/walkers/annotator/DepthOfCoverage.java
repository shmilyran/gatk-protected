package org.broadinstitute.sting.gatk.walkers.annotator;

import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;
import org.broadinstitute.sting.utils.genotype.Variation;


public class DepthOfCoverage extends StandardVariantAnnotation {

    public String annotate(ReferenceContext ref, ReadBackedPileup pileup, Variation variation) {
        int depth = pileup.getReads().size();
        return String.format("%d", depth);
    }

    public String getKeyName() { return "DP"; }

    public String getDescription() { return "DP,1,Integer,\"Total Depth\""; }

    public boolean useZeroQualityReads() { return false; }
}
