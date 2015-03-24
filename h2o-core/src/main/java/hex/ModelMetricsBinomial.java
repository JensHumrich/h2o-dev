package hex;

import water.MRTask;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.ModelUtils;

public class ModelMetricsBinomial extends ModelMetricsSupervised {
  public final AUCData _aucdata;
  public final ConfusionMatrix _cm;
  public final double _logloss;

  public ModelMetricsBinomial(Model model, Frame frame, AUCData aucdata, double logloss, double sigma, double mse) {
    super(model, frame);
    _aucdata = aucdata;
    _cm = aucdata != null ? aucdata.CM() : null;
    _sigma = sigma;
    _mse = mse;
    _logloss = logloss;
  }

  @Override public ConfusionMatrix cm() {
    return _cm;
  }
  @Override public AUCData auc() {
    return _aucdata;
  }

  public static ModelMetricsBinomial getFromDKV(Model model, Frame frame) {
    ModelMetrics mm = ModelMetrics.getFromDKV(model, frame);

    if (! (mm instanceof ModelMetricsBinomial))
      throw new H2OIllegalArgumentException("Expected to find a Binomial ModelMetrics for model: " + model._key.toString() + " and frame: " + frame._key.toString(),
              "Expected to find a ModelMetricsBinomial for model: " + model._key.toString() + " and frame: " + frame._key.toString() + " but found a: " + mm.getClass());

    return (ModelMetricsBinomial) mm;
  }

  public static class MetricBuilderBinomial extends MetricBuilderSupervised {
    protected float[] _thresholds;
    protected long[/*nthreshes*/][/*nclasses*/][/*nclasses*/] _cms; // Confusion Matric(es)
    double _logloss;
    public MetricBuilderBinomial( String[] domain, float[] thresholds ) {
      super(2,domain);
      _thresholds = thresholds;
      // Thresholds are only for binomial classes
      assert (_nclasses==2 && thresholds.length>0) || (_nclasses!=2 && thresholds.length==1);
      _cms = new long[thresholds.length][_nclasses][_nclasses];
    }

    // Passed a float[] sized nclasses+1; ds[0] must be a prediction.  ds[1...nclasses-1] must be a class
    // distribution;
    @Override public double[] perRow( double ds[], float[] yact, Model m ) {
      if( Float .isNaN(yact[0]) ) return ds; // No errors if   actual   is missing
      if( Double.isNaN(ds  [0]) ) return ds; // No errors if prediction is missing
      final int iact = (int)yact[0];

      // Compute error
//      float sum = 0;          // Check for sane class distribution
//      for( int i=1; i<ds.length; i++ ) { assert 0 <= ds[i] && ds[i] <= 1; sum += ds[i]; }
//      assert Math.abs(sum-1.0f) < 1e-6;
      double err = 1-ds[iact+1];  // Error: distance from predicting ycls as 1.0
      _sumsqe += err*err;           // Squared error
      assert !Double.isNaN(_sumsqe);

      // Binomial classification -> compute AUC, draw ROC
//      double snd = ds[2];      // Probability of a TRUE
//      // TODO: Optimize this: just keep deltas from one CM to the next
//      for(int i = 0; i < _thresholds.length; i++) {
//        int p = snd >= _thresholds[i] ? 1 : 0; // Compute prediction based on threshold
//        _cms[i][iact][p]++;   // Increase matrix
//      }

      // Compute log loss
      final double eps = 1e-15;
      if (iact == 0) {
        _logloss -= Math.log(1-Math.min(1-eps, ds[0]));
      } else {
        _logloss -= Math.log(Math.max(eps, ds[1]));
      }

      _count++;
      return ds;                // Flow coding
    }

    @Override public void reduce( MetricBuilder mb ) {
      super.reduce(mb);
//      ArrayUtils.add(_cms, ((MetricBuilderBinomial)mb)._cms);
    }

    public ModelMetrics makeModelMetrics(Model m, Frame f, Vec pred, double sigma) {
      double logloss;
      if (sigma != 0.0) {
        _thresholds = ((SupervisedModel)m).makeThresholds(null,pred);
        _cms = new ConfusionMatrixBuilder(_thresholds).doAll(pred, f.lastVec())._cms;
        ConfusionMatrix[] cms = new ConfusionMatrix[_cms.length];
        for (int i = 0; i < cms.length; i++) cms[i] = new ConfusionMatrix(_cms[i], _domain);
        AUCData aucdata = new AUC(cms, _thresholds, _domain).data();
        double mse = _sumsqe / _count;
        logloss = _logloss / _count;
        return m._output.addModelMetrics(new ModelMetricsBinomial(m, f, aucdata, logloss, sigma, mse));
      } else {
        return m._output.addModelMetrics(new ModelMetricsBinomial(m, f, null, Double.NaN, Double.NaN, Double.NaN));
      }
    }
  }

  static private class ConfusionMatrixBuilder extends MRTask<ConfusionMatrixBuilder> {
    private final float[] _thresholds; //input
    public final long[][][] _cms; //output

    ConfusionMatrixBuilder(float[] thresholds) {
      _thresholds = thresholds;
      _cms = new long[_thresholds.length][2][2];
    }

    @Override public void map(Chunk p, Chunk a) {
      for (int r = 0; r < p.len(); ++r) {
        if (p.isNA(r) || a.isNA(r)) continue;
        double pred = p.atd(r);
        int iact = (int)a.atd(r);
        for (int i = 0; i < _thresholds.length; i++) {
          int pi = pred >= _thresholds[i] ? 1 : 0;
          _cms[i][iact][pi]++;
        }
      }
    }

    @Override public void reduce(ConfusionMatrixBuilder cmb) {
      ArrayUtils.add(_cms, cmb._cms);
    }
  }
}
