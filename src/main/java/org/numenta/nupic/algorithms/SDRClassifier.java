/* ---------------------------------------------------------------------
 * Numenta Platform for Intelligent Computing (NuPIC)
 * Copyright (C) 2014, Numenta, Inc.  Unless you have an agreement
 * with Numenta, Inc., for a separate license for this software code, the
 * following terms and conditions apply:
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero Public License for more details.
 *
 * You should have received a copy of the GNU Affero Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 *
 * http://numenta.org/licenses/
 * ---------------------------------------------------------------------
 */

package org.numenta.nupic.algorithms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import no.uib.cipr.matrix.sparse.FlexCompRowMatrix;
import org.numenta.nupic.Persistable;
import org.numenta.nupic.util.ArrayUtils;
import org.numenta.nupic.util.Deque;
import org.numenta.nupic.util.Tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
//TODO: IMPLEMENT SERIALIZATION (ANG LOGGING?) LOGIC WITHIN CLASS!!!!!
/*
 * Implementation of a SDR classifier.
 * <p>
 * The SDR classifier takes the form of a single layer classification network 
 * that takes SDRs as input and outputs a predicted distribution of classes.
 * <p>
 * The SDR Classifier accepts a binary input pattern from the
 * level below (the "activationPattern") and information from the sensor and
 * encoders (the "classification") describing the true (target) input.
 * <p>
 * The SDR classifier maps input patterns to class labels. There are as many
 * output units as the number of class labels or buckets (in the case of scalar
 * encoders). The output is a probabilistic distribution over all class labels.
 * <p>
 * During inference, the output is calculated by first doing a weighted summation
 * of all the inputs, and then perform a softmax nonlinear function to get
 * the predicted distribution of class labels
 * <p>
 * During learning, the connection weights between input units and output units
 * are adjusted to maximize the likelihood of the model
 * <p>
 * The SDR Classifier is a variation of the previous CLAClassifier which was
 * not based on the references below.
 * <p>
 * Example Usage:
 *
 * c = SDRClassifier(steps=[1], alpha=0.1, actValueAlpha=0.1, verbosity=0)
 *
 * # learning
 * c.compute(recordNum=0, patternNZ=[1, 5, 9],
 *           classification={"bucketIdx": 4, "actValue": 34.7},
 *           learn=True, infer=False)
 *
 * # inference
 * result = c.compute(recordNum=1, patternNZ=[1, 5, 9],
 *                    classification={"bucketIdx": 4, "actValue": 34.7},
 *                    learn=False, infer=True)
 *
 * # Print the top three predictions for 1 steps out.
 * topPredictions = sorted(zip(result[1],
 *                         result["actualValues"]), reverse=True)[:3]
 * for probability, value in topPredictions:
 *   print "Prediction of {} has probability of {}.".format(value,
 *                                                          probability*100.0)
 *
 * References:
 *   Alex Graves. Supervised Sequence Labeling with Recurrent Neural Networks
 *   PhD Thesis, 2008
 *   J. S. Bridle. Probabilistic interpretation of feedforward classification
 *   network outputs, with relationships to statistical pattern recognition.
 *   In F. Fogleman-Soulie and J.Herault, editors, Neurocomputing: Algorithms,
 *   Architectures and Applications, pp 227-236, Springer-Verlag, 1990
 *   
 * @author Numenta
 * @author Yuwei Cui
 * @author David Ray
 * @author Andrew Dillon
 */
public class SDRClassifier {
    int verbosity = 0;
    /**
     * The alpha used to adapt the weight matrix during
     * learning. A larger alpha results in faster adaptation to the data.
     */
    double alpha = 0.001;
    /**
	 * Used to track the actual value within each
     * bucket. A lower actValueAlpha results in longer term memory
	 */
    double actValueAlpha = 0.3;
    /** 
     * The bit's learning iteration. This is updated each time store() gets
     * called on this bit.
     */
    int learnIteration;
    /**
     * This contains the offset between the recordNum (provided by caller) and
     * learnIteration (internal only, always starts at 0).
     */
    int recordNumMinusLearnIteration = -1;
	/**
	 * This contains the highest value we've ever seen from the list of active cell indexes
	 * from the TM (patternNZ). It is used to pre-allocate fixed size arrays that holds the weights.
	 */
	int maxInputIdx = 0;
    /**
     * This contains the value of the highest bucket index we've ever seen
     * It is used to pre-allocate fixed size arrays that hold the weights of
     * each bucket index during inference 
     */
    int maxBucketIdx;
	/**
	 * The connection weight matrix
	 */
	Map<Integer, FlexCompRowMatrix> weightMatrix = new HashMap<>();
    /** The sequence different steps of multi-step predictions */
    TIntList steps = new TIntArrayList();
    /**
     * History of the last _maxSteps activation patterns. We need to keep
     * these so that we can associate the current iteration's classification
     * with the activationPattern from N steps ago
     */
    Deque<Tuple> patternNZHistory;
    /**
     * These are the bit histories. Each one is a BitHistory instance, stored in
     * this dict, where the key is (bit, nSteps). The 'bit' is the index of the
     * bit in the activation pattern and nSteps is the number of steps of
     * prediction desired for that bit.
     */
    Map<Tuple, BitHistory> activeBitHistory = new HashMap<Tuple, BitHistory>();
    /**
     * This keeps track of the actual value to use for each bucket index. We
     * start with 1 bucket, no actual value so that the first infer has something
     * to return
     */
    List<?> actualValues = new ArrayList<Object>();

    /**
     * Constructor for the SDRClassifier
     * 
     * @param steps Sequence of the different steps of multi-step predictions to learn.
     * @param alpha The alpha used to adapt the weight matrix during learning. A larger alpha
     * 		  results in faster adaptation to the data.
     * @param actValueAlpha Used to track the actual value withing each bucket. A lower 
     * 		  actValueAlpha results in longer term memory.
     * @param verbosity Verbosity level, can be 0, 1, or 2.
     */
	public SDRClassifier(TIntList steps, double alpha, double actValueAlpha, int verbosity) {
        this.steps = steps;
        this.alpha = alpha;
        this.actValueAlpha = actValueAlpha;
        this.verbosity = verbosity;
        actualValues.add(null);
        patternNZHistory = new Deque<Tuple>(ArrayUtils.max(steps.toArray()) + 1);
		for(int step : steps.toArray())
			weightMatrix.put(step, new FlexCompRowMatrix(maxBucketIdx + 1, maxInputIdx + 1));
	}

	/**
	 * Process one input sample.
	 * This method is called by outer loop code outside the nupic-engine. We 
	 * use this instead of the nupic engine compute() because our inputs and 
	 * outputs aren't fixed size vectors of reals.
	 * <p>
	 * @param recordNum <p>
	 * Record number of this input pattern. Record numbers normally increase
	 * sequentially by 1 each time unless there are missing records in the
	 * dataset. Knowing this information ensures that we don't get confused by
	 * missing records.
	 * @param classification <p>
	 * {@link Map} of the classification information:
	 * <p>&emsp;"bucketIdx" - index of the encoder bucket
	 * <p>&emsp;"actValue" -  actual value doing into the encoder
	 * @param patternNZ <p>
	 * List of the active indices from the output below. When the output is from
	 * the TemporalMemory, this array should be the indices of the active cells.
	 * @param learn <p>
	 * If true, learn this sample.
	 * @param infer <p>
	 * If true, perform inference.
	 * 
	 * @return
	 * {@link Classification} containing inference results. The Classification
	 * contains the computed probability distribution (relative likelihood for each
	 * bucketIdx starting from bucketIdx 0) for each step in {@code steps}. Each bucket's
	 * likelihood can be accessed individually, or all the buckets' likelihoods can
	 * be obtained in the form of a double array.
	 *
<pre>{@code
//Get likelihood val for bucket 0, 5 steps in future
classification.getStat(5, 0);

//Get all buckets' likelihoods as double[] where each
//index is the likelihood for that bucket
//(e.g. [0] contains likelihood for bucketIdx 0)
classification.getStats(5);
}</pre>
	 *
	 * The Classification also contains the average actual value for each bucket, if
	 * the type being predicted is numerical in nature. The average values for the
	 * buckets can be accessed individually, or altogether as a double[].
	 *
<pre>{@code
//Get average actual val for bucket 0
classification.getActualValue(0);

//Get average vals for all buckets as double[], where
//each index is the average val for that bucket
//(e.g. [0] contains average val for bucketIdx 0)
classification.getActualValues();
}</pre>
	 *
	 * The Classification can also be queried for the most probable bucket (the bucket
	 * with the highest associated likelihood value), as well as the average input value
	 * that corresponds to that bucket.
	 *
<pre>{@code
//Get index of most probable bucket
classification.getMostProbableBucketIndex();

//Get the average actual val for that bucket
classification.getMostProbableValue();
}</pre>
	 *
	 */
    @SuppressWarnings("unchecked")
    public <T> Classification<T> compute(int recordNum, Map<String, Object> classification, int[] patternNZ, boolean learn, boolean infer) {
        Classification<T> retVal = new Classification<T>();
        List<T> actualValues = (List<T>)this.actualValues;

		//Save the offset between recordNum and learnIteration if this is the first compute
		if(recordNumMinusLearnIteration == -1)
			recordNumMinusLearnIteration = recordNum - learnIteration;

		//Update the learn iteration
		learnIteration = recordNum - recordNumMinusLearnIteration;

		//Verbose print
		if(verbosity >= 1) {
			System.out.printf("recordNum: %d", recordNum);
			System.out.printf("learnIteration: %d", learnIteration);
			System.out.printf("patternNZ (%d): %d", patternNZ.length, patternNZ);
			System.out.println("classificationIn: " + classification);
		}

		//Store pattern in our history
		patternNZHistory.append(new Tuple(learnIteration, patternNZ));

		//Update maxInputIdx and augment weight matrix with zero padding
		//TODO: port lines 225-232 of python version here: !SHOULD BE DONE NOW!
		if(ArrayUtils.max(patternNZ) > maxInputIdx) {
			int newMaxInputIdx = Math.max(ArrayUtils.max(patternNZ), maxBucketIdx);
			for (int nSteps : steps.toArray()) {
				for(int i = maxBucketIdx; i < ArrayUtils.max(patternNZ); i++) {
					weightMatrix.get(nSteps).addCol(new double[maxBucketIdx + 1]);
				}
			}
			maxInputIdx = newMaxInputIdx;
		}

		//------------------------------------------------------------------------
		//Inference:
		//For each active bit in the activationPattern, get the classification votes
		if(infer) {
			//TODO: IMPLEMENT INFERENCE LOGIC HERE - LINE 239 IN PYTHON VERSION
		}

		//------------------------------------------------------------------------
		//Learning:
		if(learn && classification.get("bucketIdx") != null) {
			//TODO: IMPLEMENT LEARNING LOGIC HERE - LINES 249-280 IN PYTHON VERSION
			// Get classification info
			int bucketIdx = (int)classification.get("bucketIdx");
			Object actValue = classification.get("actValue");

			// Update maxBucketIndex and augment weight matrix with zero padding
			if(bucketIdx > maxBucketIdx) {
				for(int nSteps : steps.toArray()) {
					for(int i = maxBucketIdx; i < bucketIdx; i++) {
						weightMatrix.get(nSteps).addRow(new double[maxBucketIdx + 1]);
					}
				}
				maxBucketIdx = bucketIdx;
			}


			// Update rolling average of actual values if it's a scalar. If it's not, it
			// must be a category, in which case each bucket only ever sees on category so
			// we don't need a running average.
			while(maxBucketIdx > actualValues.size() - 1) {
				actualValues.add(null);
			}
			if(actualValues.get(bucketIdx) == null) {
				actualValues.set(bucketIdx, (T)actValue);
			}
			else {
				if(Number.class.isAssignableFrom(actValue.getClass())) {
					Double val = ((1.0 - actValueAlpha) * ((Number)actualValues.get(bucketIdx)).doubleValue() +
							actValueAlpha * ((Number)actValue).doubleValue());
					actualValues.set(bucketIdx, (T)val);
				}else{
					actualValues.set(bucketIdx, (T)actValue);
				}
			}

			int iteration = 0;
			int[] learnPatternNZ = null;
			for(Tuple t : patternNZHistory) {
				iteration = (int)t.get(0);
				learnPatternNZ = (int[])t.get(1);

				//TODO: PORT THE FOLLOWING LINE - PYTHON VERSION LINE 275
				double[] error = calculateError(classification);

				int nSteps = learnIteration - iteration;
				if(steps.contains(nSteps)) {
					for(int row = 0; row <= maxBucketIdx; row++) {
						for (int bit : learnPatternNZ) {
							weightMatrix.get(nSteps).add(row, bit, alpha * error[nSteps]);
						}
					}
				}
			}
		}

		//------------------------------------------------------------------------
		//Verbose Print
		//TODO: implement the verbose print here - lines 285-295 in python version !SHOULD BE DONE!
		if(infer && verbosity >= 1) {
			System.out.println(" inference: combined bucket likelihoods:");
			System.out.println("   actual bucket values: " + Arrays.toString((T[])retVal.getActualValues()));

			for(int key : retVal.stepSet()) {
				if(retVal.getActualValue(key) == null) continue;

				Object[] actual = new Object[] { (T)retVal.getActualValue(key) };
				System.out.println(String.format("  %d steps: ", key, pFormatArray(actual)));
				int bestBucketIdx = retVal.getMostProbableBucketIndex(key);
				System.out.println(String.format("   most likely bucket idx: %d, value: %s ", bestBucketIdx,
						retVal.getActualValue(bestBucketIdx)));

			}
		}

		return retVal;
    }

	//TODO: IMPLEMENT calculateError(self, classification) HERE - LINES 446-466 OF PYTHON VERSION

	/**
	 * Return a string with pretty-print of an array using the given format
	 * for each element
	 *
	 * @param arr
	 * @return
	 */
	private <T> String pFormatArray(T[] arr) {
		if(arr == null) return "";

		StringBuilder sb = new StringBuilder("[ ");
		for(T t : arr) {
			sb.append(String.format("%.2s", t));
		}
		sb.append(" ]");
		return sb.toString();
	}
}