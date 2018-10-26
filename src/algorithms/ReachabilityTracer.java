package algorithms;

import models.StateSpace;
import java.util.Random;
import nl.utwente.ewi.fmt.EXPRES.Property;

/** Tracer to estimate the probability of hitting a red state before
 * hitting either a blue state of the time bound.
 */
public class ReachabilityTracer extends TraceGenerator
{
	private long N;
	private long M;
	private double sum;
	private double sumSquares;
	private double estMean;
	private final double forceBound;
	private final double UNIF_BOUND = 1e-10;

	public ReachabilityTracer(Random rng, Scheme s, Property prop,
	                          double forceBound)
	{
		super(rng, s, prop);
		this.forceBound = forceBound;
	}

	public TraceGenerator copy()
	{
		ReachabilityTracer ret;
		ret = new ReachabilityTracer(subRNG(), scheme.clone(),
		                             prop, forceBound);
		ret.estMean = estMean;
		return ret;
	}

	public void reset()
	{
		super.reset();
		N = M = 0;
		sum = sumSquares = estMean = 0;
	}

	public void resetAndEstimateMeans()
	{
		double newEstMean = 0;
		if (N > 0)
			newEstMean = sum / N;
		super.resetAndEstimateMeans();
		estMean = newEstMean;
	}

	public void resetAndEstimateMeans(TraceGenerator[] ts)
	{
		double sum = 0;
		long N = 0;
		super.resetAndEstimateMeans(ts);
		for (TraceGenerator t : ts) {
			if (t instanceof ReachabilityTracer) {
				sum += ((ReachabilityTracer)t).sum;
				N += ((ReachabilityTracer)t).N;
			}
		}
		estMean = sum / N;
	}

	private double computeProb(int[] path)
	{
		double[] exitRates = scheme.model.exitRates;
		double unifRate = Double.NEGATIVE_INFINITY;
		int totalCount = 0;
		for (int i = 0; i < path.length; i++) {
			if (path[i] > 0) {
				Double rate = exitRates[i];
				if (rate > unifRate)
					unifRate = rate;
				totalCount += path[i];
			}
		}
		double rates[] = new double[totalCount + 1];
		double probs[] = new double[totalCount + 1];
		probs[0] = 1;
		double undecProb = 1;
		double reachProb = 0;
		double prob = Math.exp(-unifRate * prop.timeBound);
		int k = 0;
		for (int i = 0; i < path.length; i++) {
			if (path[i] > 0) {
				double rate = exitRates[i] / unifRate;
				while (path[i] --> 0)
					rates[k++] = rate;
			}
		}
		k = 1;
		do {
			double nextProb = 0;
			double nextRate = 0;
			int i;
			undecProb -= prob;
			prob *= (unifRate * prop.timeBound) / k++;
			for (i = 0; i < rates.length; i++) {
				double tmp;
				tmp = probs[i];
				probs[i] = -Math.fma(probs[i], rates[i], -probs[i]);
				probs[i] = Math.fma(nextProb, nextRate, probs[i]);
				nextProb = tmp;
				nextRate = rates[i];
			}
			reachProb = Math.fma(probs[i - 1], prob, reachProb);
		} while (undecProb > UNIF_BOUND);
		return reachProb;
	}

	public void sample()
	{
		int state = 0;
		double time = 0;
		double likelihood = 1;
		int path[] = null;
		StateSpace model = scheme.model;
		if (forceBound < 0 && Double.isFinite(prop.timeBound))
			path = new int[model.exitRates.length];

		do {
			state = drawNextState(state);
			if (path != null) {
				path = extendPath(path);
			} else if (prop.timeBound < Double.POSITIVE_INFINITY) {
				time += drawDelta(prop.timeBound - time);
			}
			likelihood *= likelihood();
		} while(!model.isRed(state) && !model.isBlue(state)
		        && time < prop.timeBound
		        && likelihood > 0);

		N++;
		if(time < prop.timeBound && model.isRed(state)) {
			M++;
			double prob = 1;
			if (path != null)
				prob = computeProb(path);
			sum = Math.fma(prob, likelihood, sum);
			prob = Math.fma(prob, likelihood, -estMean);
			sumSquares = Math.fma(prob, prob, sumSquares);
		}
	}

	public SimulationResult getResult(double alpha)
	{
		long time = getElapsedTime();
		double mean = sum / N;
		double estSum = Math.fma(-N, estMean, sum);
		double var = Math.fma(-estSum, mean - estMean, sumSquares);
		var /= N - 1;
		SimulationResult ret = null;
		if (scheme.isBinomial())
			ret = binomialCI(alpha, var, time);

		if (ret == null)
			ret = new SimulationResult(prop, alpha, mean, var, new long[]{N, M}, time, baseModelSize);
		return ret;
	}

	public SimulationResult getResult(TraceGenerator[] ts, double alpha)
	{
		sum = 0;
		N = M = 0;
		for (TraceGenerator t : ts) {
			if (t instanceof ReachabilityTracer) {
				ReachabilityTracer rt = (ReachabilityTracer)t;
				if (estMean != rt.estMean) {
					sum = 0;
					sumSquares = 0;
					N = M = 0;
				}
				estMean = rt.estMean;
				sum += rt.sum;
				sumSquares += rt.sumSquares;
				N += rt.N;
				M += rt.M;
			}
		}
		return getResult(alpha);
	}

	private static long gcd(long a, long b)
	{
		long t;
		while (b != 0) {
			t = b;
			b = a % b;
			a = t;
		}
		return a;
	}

	private static long binomCoeff(long n, long k)
	{
		long ret = 1;
                long denom = 1, numer = 1;
                if (k > n - k)
                        k = n - k;
                for (int i = 1; i <= k; i++) {
			long g;
			long newn = n + 1 - i, newd = i;
			g = gcd(newn, newd);
			newn /= g;
			newd /= g;
			g = gcd(newn, denom);
			newn /= g;
			denom /= g;
			g = gcd(numer, newd);
                        numer /= g;
                        newd /= g;
                        numer = Math.multiplyExact(numer, newn);
                        denom = Math.multiplyExact(denom, newd);
                }
                return numer / denom;
	}

	private SimulationResult binomialCI(double alpha, double var, long time)
	{
		double mean, lbound, ubound;
		boolean inverse = false;
		long M = this.M;
		if (M > N / 2) {
			M = N - M;
			inverse = true;
		}
		if (M == 0) {
			mean = 0;
			if (!inverse) {
				lbound = 0;
				ubound = 1 - Math.pow(alpha / 2, 1.0 / N);
			} else {
				ubound = 1;
				lbound = Math.pow(alpha / 2, 1.0 / N);
			}
		} else if (M <= 1024) {
			mean = M / (double)N;
			long coeffs[] = new long[(int)M + 1];
			try {
				for (int i = 0; i <= M; i++)
					coeffs[i] = binomCoeff(N, i);
			} catch (ArithmeticException e) {
				return null;
			}
			double minP = 0, maxP = 1;
			lbound = (maxP + minP) / 2;
			while (lbound != maxP && lbound != minP) {
				double cdf = 0;
				double v1, v2;
				for (int i = 0; i <= M - 1; i++) {
					v1 = Math.pow(lbound, i) * coeffs[i];
					v2 = Math.pow(1 - lbound, N - i);
					cdf = Math.fma(v1, v2, cdf);
				}
				if (1 - cdf > alpha / 2)
					maxP = lbound;
				else
					minP = lbound;
				lbound = (maxP + minP) / 2;
			}

			minP = 0;
			maxP = 1;
			ubound = (maxP + minP) / 2;
			while (ubound != maxP && ubound != minP) {
				double cdf = 0;
				double v1, v2;
				for (int i = 0; i <= M; i++) {
					v1 = Math.pow(ubound, i) * coeffs[i];
					v2 = Math.pow(1 - ubound, N - i);
					cdf = Math.fma(v1, v2, cdf);
				}
				if (cdf > alpha / 2)
					minP = ubound;
				else
					maxP = ubound;
				ubound = (maxP + minP) / 2;
			}
			if (inverse) {
				minP = 1 - lbound;
				lbound = 1 - ubound;
				ubound = minP;
			}
		} else {
			return null;
		}
		return new SimulationResult(prop, mean, alpha, var, lbound, ubound, new long[]{N, M}, time, baseModelSize);
	}
}
