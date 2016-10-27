package org.nd4j.linalg.lossfunctions.impl;

import lombok.EqualsAndHashCode;
import org.apache.commons.math3.util.Pair;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.transforms.LogSoftMax;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.lossfunctions.serde.RowVectorDeserializer;
import org.nd4j.linalg.lossfunctions.serde.RowVectorSerializer;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.shade.jackson.annotation.JsonInclude;
import org.nd4j.shade.jackson.annotation.JsonProperty;
import org.nd4j.shade.jackson.databind.annotation.JsonDeserialize;
import org.nd4j.shade.jackson.databind.annotation.JsonSerialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by susaneraly on 8/22/16.
 */
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LossBinaryXENT implements ILossFunction {

    @JsonSerialize(using = RowVectorSerializer.class)
    @JsonDeserialize(using = RowVectorDeserializer.class)
    private final INDArray weights;

    public LossBinaryXENT() {
        this(null);
    }

    public LossBinaryXENT(INDArray weights) {
        if (weights != null && !weights.isRowVector()) {
            throw new IllegalArgumentException("Weights array must be a row vector");
        }
        this.weights = weights;
    }

    private INDArray scoreArray(INDArray labels, INDArray preOutput, String activationFn, INDArray mask) {
        INDArray scoreArr;
        if ("softmax".equals(activationFn)) {
            //Use LogSoftMax op to avoid numerical issues when calculating score
            INDArray logsoftmax = Nd4j.getExecutioner().execAndReturn(new LogSoftMax(preOutput.dup()));
            scoreArr = logsoftmax.muli(labels);

        } else {
            INDArray output = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(activationFn, preOutput.dup()));
            scoreArr = Transforms.log(output, true).muli(labels);
            INDArray secondTerm = output.rsub(1);
            Transforms.log(secondTerm, false);
            secondTerm.muli(labels.rsub(1));
            scoreArr.addi(secondTerm);
        }

        //Weighted loss function
        if (weights != null) {
            if (weights.length() != preOutput.size(1)) {
                throw new IllegalStateException("Weights vector (length " + weights.length() + ") does not match output.size(1)=" + preOutput.size(1));
            }
            scoreArr.muliRowVector(weights);
        }

        if (mask != null) {
            scoreArr.muliColumnVector(mask);
        }
        return scoreArr;
    }

    @Override
    public double computeScore(INDArray labels, INDArray preOutput, String activationFn, INDArray mask, boolean average) {
        INDArray scoreArr = scoreArray(labels, preOutput, activationFn, mask);

        double score = -scoreArr.sumNumber().doubleValue();

        if (average) {
            score /= scoreArr.size(0);
        }

        return score;
    }

    @Override
    public INDArray computeScoreArray(INDArray labels, INDArray preOutput, String activationFn, INDArray mask) {
        INDArray scoreArr = scoreArray(labels, preOutput, activationFn, mask);
        return scoreArr.sum(1).muli(-1);
    }

    @Override
    public INDArray computeGradient(INDArray labels, INDArray preOutput, String activationFn, INDArray mask) {
        INDArray grad;
        INDArray output = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(activationFn, preOutput.dup()));

        if ("softmax".equals(activationFn)) {
            if(weights != null){
                if(weights.length() != output.size(1)){
                    throw new IllegalStateException("Weights vector (length " + weights.length() + ") does not match output.size(1)=" + output.size(1));
                }
                INDArray temp = labels.mulRowVector(weights);
                INDArray col = temp.sum(1);
                grad = output.mulColumnVector(col).sub(temp);
            } else {
                grad = output.subi(labels);
            }
        } else {
            // So, the derivative of XE(preoutput, label, activation) wrt preoutput is
            // for sanity sake, we'll call activation(preoutput) = a and activation'(preoutput) = a'
            // XE = label * log(a) + (1-label) * log(1-a)
            // d XE/d preoutput = a' * (label - a) / (a * (1-a))
            grad = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(activationFn, preOutput.dup()).derivative());
            INDArray denominator = output.mul(output.rsub(1)); // output * (1-output)
            INDArray numerator = output.sub(labels);
            grad.muli(numerator).divi(denominator);

            //Weighted loss function
            if (weights != null) {
                if (weights.length() != output.size(1)) {
                    throw new IllegalStateException("Weights vector (length " + weights.length() + ") does not match output.size(1)=" + output.size(1));
                }
                grad.muliRowVector(weights);
            }
        }

        if (mask != null) {
            grad.muliColumnVector(mask);
        }

        return grad;
    }

    @Override
    public Pair<Double, INDArray> computeGradientAndScore(INDArray labels, INDArray preOutput, String activationFn, INDArray mask, boolean average) {
        //TODO: probably a more efficient way to do this...

        return new Pair<>(
                computeScore(labels, preOutput, activationFn, mask, average),
                computeGradient(labels, preOutput, activationFn, mask));
    }


    @Override
    public String toString() {
        return "LossBinaryXENT()";
    }
}
