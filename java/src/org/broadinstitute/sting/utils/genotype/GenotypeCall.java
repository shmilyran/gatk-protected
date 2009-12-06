package org.broadinstitute.sting.utils.genotype;


/**
 * @author ebanks
 *         <p/>
 *         Class GenotypeCall
 *         <p/>
 *         represents the genotype-specific data associated with a genotype call.
 */
public interface GenotypeCall extends Genotype {

    /**
     *
     * @param   variation    the Variation object associated with this genotype
     */
    public void setVariation(Variation variation);

}