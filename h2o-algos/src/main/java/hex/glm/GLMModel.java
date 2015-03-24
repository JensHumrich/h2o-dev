package hex.glm;

import hex.*;
import hex.ModelMetrics.MetricBuilder;
import hex.ModelMetricsBinomial.MetricBuilderBinomial;
import hex.glm.GLMModel.GLMParameters.Family;
import water.*;
import water.DTask.DKeyTask;
import water.H2O.H2OCountedCompleter;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ModelUtils;
import water.util.TwoDimTable;

import java.util.Arrays;
import java.util.HashMap;
/**
 * Created by tomasnykodym on 8/27/14.
 */
public class GLMModel extends SupervisedModel<GLMModel,GLMModel.GLMParameters,GLMModel.GLMOutput> {
  final DataInfo _dinfo;
  public GLMModel(Key selfKey, GLMParameters parms, GLMOutput output, DataInfo dinfo, double ymu, double lambda_max, long nobs, float [] thresholds) {
    super(selfKey, parms, output);
    _ymu = ymu;
    _lambda_max = lambda_max;
    _nobs = nobs;
    _dinfo = dinfo;
    _defaultThresholds = thresholds;
  }

  float [] _defaultThresholds;

  public static class GLMMetricsBuilderBinomial extends MetricBuilderBinomial {
    double _resDev;
    double _nullDev;

    public GLMMetricsBuilderBinomial(String[] domain, float[] thresholds) {
      super(domain == null?new String[]{"0","1"}:domain, thresholds);
    }

    private int rank(double [] beta) {
      int res = 0;
      for(double d:beta)
        if(d != 0) ++res;
      return res;
    }


    @Override
    public double[] perRow(double[] ds, float[] yact, Model m) {
      double [] res = super.perRow(ds,yact,m);
      GLMModel gm = (GLMModel)m;
      assert gm._parms._family == Family.binomial;
      _resDev += gm._parms.deviance(yact[0], (float)ds[2]);
      _nullDev += gm._parms.deviance(yact[0],(float)gm._ymu);
      return res;
    }

    @Override public void reduce( MetricBuilder mb ) {
      super.reduce(mb);
      GLMMetricsBuilderBinomial mg = (GLMMetricsBuilderBinomial)mb;
      _resDev += mg._resDev;
      _nullDev += mg._nullDev;
    }

    @Override
    public ModelMetrics makeModelMetrics(Model m, Frame f, Vec pred, double sigma) {
      GLMModel gm = (GLMModel)m;
      assert gm._parms._family == Family.binomial;
      ConfusionMatrix[] cms = new ConfusionMatrix[_cms.length];
      for( int i=0; i<cms.length; i++ ) cms[i] = new ConfusionMatrix(_cms[i], _domain);
      AUCData aucdata = new AUC(cms,_thresholds,_domain).data();
      double mse = _sumsqe / _count;
      ModelMetrics res = new ModelMetricsBinomialGLM(m, f, aucdata, sigma, mse, _resDev, _nullDev, _resDev + 2*rank(gm.beta()));
      return m._output.addModelMetrics(res);
    }
  }
  public static class GetScoringModelTask extends DTask.DKeyTask<GetScoringModelTask,GLMModel> {
    final double _lambda;
    public GLMModel _res;
    public GetScoringModelTask(H2OCountedCompleter cmp, Key modelKey, double lambda){
      super(cmp,modelKey);
      _lambda = lambda;
    }
    @Override
    public void map(GLMModel m) {
      _res = m.clone();
      _res._output = (GLMOutput)_res._output.clone();
      Submodel sm = Double.isNaN(_lambda)?_res._output._submodels[_res._output._best_lambda_idx]:_res._output.submodelForLambda(_lambda);
      assert sm != null : "GLM[" + m._key + "]: missing submodel for lambda " + _lambda;
      sm = (Submodel) sm.clone();
      _res._output._submodels = new Submodel[]{sm};
      _res._output.setSubmodelIdx(0);
    }
  }

  @Override public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    switch(_output.getModelCategory()) {
      case Binomial: return new GLMMetricsBuilderBinomial(domain, ModelUtils.DEFAULT_THRESHOLDS);
      case Regression: return new ModelMetricsRegression.MetricBuilderRegression();
      default: throw H2O.unimpl();
    }
  }

  public double [] beta(){ return _output._global_beta;}

  public GLMValidation validation(){
    return _output._submodels[_output._best_lambda_idx].validation;
  }

  @Override
  public double[] score0(Chunk[] chks, int row_in_chunk, double[] tmp, double[] preds) {
    double eta = 0.0;
    final double [] b = beta();
    if(!_parms._use_all_factor_levels){ // good level 0 of all factors
      for(int i = 0; i < _dinfo._catOffsets.length-1; ++i) if(chks[i].atd(row_in_chunk) != 0)
        eta += b[_dinfo._catOffsets[i] + (int)(chks[i].atd(row_in_chunk)-1)];
    } else { // do not good any levels!
      for(int i = 0; i < _dinfo._catOffsets.length-1; ++i)
        eta += b[_dinfo._catOffsets[i] + (int)chks[i].atd(row_in_chunk)];
    }
    final int noff = _dinfo.numStart() - _dinfo._cats;
    for(int i = _dinfo._cats; i < b.length-1-noff; ++i)
      eta += b[noff+i]*chks[i].atd(row_in_chunk);
    eta += b[b.length-1]; // add intercept
    double mu = _parms.linkInv(eta);
    preds[0] = mu;
    if( _parms._family == Family.binomial ) { // threshold for prediction
      if(Double.isNaN(mu)){
        preds[0] = Double.NaN;
        preds[1] = Double.NaN;
        preds[2] = Double.NaN;
      } else {
        preds[0] = (mu >= _output._threshold ? 1 : 0);
        preds[1] = 1.0 - mu; // class 0
        preds[2] =       mu; // class 1
      }
    }
    return preds;
  }

  @Override
  protected double[] score0(double[] data, double[] preds) {
    double eta = 0.0;
    final double [] b = beta();
    if(!_parms._use_all_factor_levels){ // good level 0 of all factors
      for(int i = 0; i < _dinfo._catOffsets.length-1; ++i) if(data[i] != 0)
        eta += b[_dinfo._catOffsets[i] + (int)(data[i]-1)];
    } else { // do not good any levels!
      for(int i = 0; i < _dinfo._catOffsets.length-1; ++i)
        eta += b[_dinfo._catOffsets[i] + (int)data[i]];
    }
    final int noff = _dinfo.numStart() - _dinfo._cats;
    for(int i = _dinfo._cats; i < data.length; ++i)
      eta += b[noff+i]*data[i];
    eta += b[b.length-1]; // add intercept
    double mu = _parms.linkInv(eta);
    preds[0] = mu;
    if( _parms._family == Family.binomial ) { // threshold for prediction
      if(Double.isNaN(mu)){
        preds[0] = Double.NaN;
        preds[1] = Double.NaN;
        preds[2] = Double.NaN;
      } else {
        preds[0] = (mu >= _output._threshold ? 1 : 0);
        preds[1] = 1.0 - mu; // class 0
        preds[2] =       mu; // class 1
      }
    }
    return preds;
  }

  public static class GLMParameters extends SupervisedModel.SupervisedParameters {
    // public int _response; // TODO: the standard is now _response_column in SupervisedModel.SupervisedParameters
    public boolean _standardize = true;
    public final Family _family;
    public Link _link;
    public Solver _solver = Solver.ADMM;
    public final double _tweedie_variance_power;
    public final double _tweedie_link_power;
    public double [] _alpha;
    public double [] _lambda;
    public double _prior = -1;
    public boolean _lambda_search = false;
    public int _nlambdas = -1;
    public double _lambda_min_ratio = -1; // special
    public boolean _use_all_factor_levels = false;
    public double _beta_epsilon = 1e-4;
    public int _max_iter = 50;
    public int _n_folds;

    public Key<Frame> _beta_constraint = null;
    // internal parameter, handle with care. GLM will stop when there is more than this number of active predictors (after strong rule screening)
    public int _max_active_predictors = 10000; // NOTE: Not brought out to the REST API

    public void validate(GLM glm) {
      if(_family == Family.binomial) {
        Frame frame = DKV.getGet(_train);
        if (frame != null) {
          Vec response = frame.vec(_response_column);
          if (response != null) {
            if (response.min() != 0 || response.max() != 1) {
              glm.error("_response_column", "Illegal response for family binomial, must be binary, got min = " + response.min() + ", max = " + response.max() + ")");
            }
          }
        }
      }
      if (_solver == Solver.L_BFGS) {
        glm.hide("_alpha", "L1 penalty is currently only available for ADMM solver.");
        glm.hide("_higher_accuracy","only available for ADMM");
        _alpha = new double[]{0};
      }
      if(!_lambda_search) {
        glm.hide("_lambda_min_ratio", "only applies if lambda search is on.");
        glm.hide("_nlambdas", "only applies if lambda search is on.");
      }
      if(_link != Link.family_default) { // check we have compatible link
        switch (_family) {
          case gaussian:
            if (_link != Link.identity && _link != Link.log && _link != Link.inverse)
              throw new IllegalArgumentException("Incompatible link function for selected family. Only identity, log and inverse links are allowed for family=gaussian.");
            break;
          case binomial:
            if (_link != Link.logit && _link != Link.log)
              throw new IllegalArgumentException("Incompatible link function for selected family. Only logit and log links are allowed for family=binomial.");
            break;
          case poisson:
            if (_link != Link.log && _link != Link.identity)
              throw new IllegalArgumentException("Incompatible link function for selected family. Only log and identity links are allowed for family=poisson.");
            break;
          case gamma:
            if (_link != Link.inverse && _link != Link.log && _link != Link.identity)
              throw new IllegalArgumentException("Incompatible link function for selected family. Only inverse, log and identity links are allowed for family=gamma.");
            break;
          case tweedie:
            if (_link != Link.tweedie)
              throw new IllegalArgumentException("Incompatible link function for selected family. Only tweedie link allowed for family=tweedie.");
            break;
          default:
            H2O.fail();
        }
      }
    }

    public GLMParameters(){
      this(Family.gaussian, Link.family_default);
      assert _link == Link.family_default;
    }
    public GLMParameters(Family f){this(f,f.defaultLink);}
    public GLMParameters(Family f, Link l){this(f,l,new double[]{1e-5},new double[]{.5});}
    public GLMParameters(Family f, Link l, double [] lambda, double [] alpha){
      this._family = f;
      this._lambda = lambda;
      this._alpha = alpha;
      _tweedie_link_power = Double.NaN;
      _tweedie_variance_power = Double.NaN;
      _link = l;
    }
    public GLMParameters(Family f, double [] lambda, double [] alpha, double twVar, double twLnk){
      this._lambda = lambda;
      this._alpha = alpha;
      this._tweedie_variance_power = twVar;
      this._tweedie_link_power = twLnk;
      _family = f;
      _link = f.defaultLink;
    }

    public final double variance(double mu){
      switch(_family) {
        case gaussian:
          return 1;
        case binomial:
//        assert (0 <= mu && mu <= 1) : "mu out of bounds<0,1>:" + mu;
          return mu * (1 - mu);
        case poisson:
          return mu;
        case gamma:
          return mu * mu;
        case tweedie:
          return Math.pow(mu, _tweedie_variance_power);
        default:
          throw new RuntimeException("unknown family Id " + this);
      }
    }

    public double [] nullModelBeta(DataInfo dinfo, double ymu){
      double [] res = MemoryManager.malloc8d(dinfo.fullN() + 1);
      res[res.length-1] = link(ymu);
      return res;
    }

    public final boolean canonical(){
      switch(_family){
        case gaussian:
          return _link == Link.identity;
        case binomial:
          return _link == Link.logit;
        case poisson:
          return _link == Link.log;
        case gamma:
          return false; //return link == Link.inverse;
        case tweedie:
          return false;
        default:
          throw H2O.unimpl();
      }
    }

    public final double mustart(double y, double ymu) {
      switch(_family) {
        case gaussian:
        case binomial:
        case poisson:
          return ymu;
        case gamma:
          return y;
        case tweedie:
          return y + (y==0?0.1:0);
        default:
          throw new RuntimeException("unimplemented");
      }
    }

    public final double deviance(double yr, double eta, double ym){
      switch(_family){
        case gaussian:
          return (yr - ym) * (yr - ym);
        case binomial:
//          if(yr == ym) return 0;
//          return 2*( -yr * eta - Math.log(1 - ym));
          return 2 * ((y_log_y(yr, ym)) + y_log_y(1 - yr, 1 - ym));
        case poisson:
          if( yr == 0 ) return 2 * ym;
          return 2 * ((yr * Math.log(yr / ym)) - (yr - ym));
        case gamma:
          if( yr == 0 ) return -2;
          return -2 * (Math.log(yr / ym) - (yr - ym) / ym);
        case tweedie:
          // Theory of Dispersion Models: Jorgensen
          // pg49: $$ d(y;\mu) = 2 [ y \cdot \left(\tau^{-1}(y) - \tau^{-1}(\mu) \right) - \kappa \{ \tau^{-1}(y)\} + \kappa \{ \tau^{-1}(\mu)\} ] $$
          // pg133: $$ \frac{ y^{2 - p} }{ (1 - p) (2-p) }  - \frac{y \cdot \mu^{1-p}}{ 1-p} + \frac{ \mu^{2-p} }{ 2 - p }$$
          double one_minus_p = 1 - _tweedie_variance_power;
          double two_minus_p = 2 - _tweedie_variance_power;
          return Math.pow(yr, two_minus_p) / (one_minus_p * two_minus_p) - (yr * (Math.pow(ym, one_minus_p)))/one_minus_p + Math.pow(ym, two_minus_p)/two_minus_p;
        default:
          throw new RuntimeException("unknown family " + _family);
      }
    }
    public final double deviance(float yr, float ym){
      switch(_family){
        case gaussian:
          return (yr - ym) * (yr - ym);
        case binomial:
//          if(yr == ym) return 0;
//          return 2*( -yr * eta - Math.log(1 - ym));
          return 2 * ((y_log_y(yr, ym)) + y_log_y(1 - yr, 1 - ym));
        case poisson:
          if( yr == 0 ) return 2 * ym;
          return 2 * ((yr * Math.log(yr / ym)) - (yr - ym));
        case gamma:
          if( yr == 0 ) return -2;
          return -2 * (Math.log(yr / ym) - (yr - ym) / ym);
        case tweedie:
          // Theory of Dispersion Models: Jorgensen
          // pg49: $$ d(y;\mu) = 2 [ y \cdot \left(\tau^{-1}(y) - \tau^{-1}(\mu) \right) - \kappa \{ \tau^{-1}(y)\} + \kappa \{ \tau^{-1}(\mu)\} ] $$
          // pg133: $$ \frac{ y^{2 - p} }{ (1 - p) (2-p) }  - \frac{y \cdot \mu^{1-p}}{ 1-p} + \frac{ \mu^{2-p} }{ 2 - p }$$
          double one_minus_p = 1 - _tweedie_variance_power;
          double two_minus_p = 2 - _tweedie_variance_power;
          return Math.pow(yr, two_minus_p) / (one_minus_p * two_minus_p) - (yr * (Math.pow(ym, one_minus_p)))/one_minus_p + Math.pow(ym, two_minus_p)/two_minus_p;
        default:
          throw new RuntimeException("unknown family " + _family);
      }
    }

    public final double likelihood(double yr, double eta, double ym){
      switch(_family){
        case gaussian:
          return .5 * (yr - ym) * (yr - ym);
        case binomial:
          if(yr == ym) return 0;
          return Math.log(1 + Math.exp((1 - 2*yr) * eta));
//
//          double res = -yr * eta - Math.log(1 - ym);
//          return res;
        case poisson:
          if( yr == 0 ) return 2 * ym;
          return 2 * ((yr * Math.log(yr / ym)) - (yr - ym));
        case gamma:
          if( yr == 0 ) return -2;
          return -2 * (Math.log(yr / ym) - (yr - ym) / ym);
        case tweedie:
          // Theory of Dispersion Models: Jorgensen
          // pg49: $$ d(y;\mu) = 2 [ y \cdot \left(\tau^{-1}(y) - \tau^{-1}(\mu) \right) - \kappa \{ \tau^{-1}(y)\} + \kappa \{ \tau^{-1}(\mu)\} ] $$
          // pg133: $$ \frac{ y^{2 - p} }{ (1 - p) (2-p) }  - \frac{y \cdot \mu^{1-p}}{ 1-p} + \frac{ \mu^{2-p} }{ 2 - p }$$
          double one_minus_p = 1 - _tweedie_variance_power;
          double two_minus_p = 2 - _tweedie_variance_power;
          return Math.pow(yr, two_minus_p) / (one_minus_p * two_minus_p) - (yr * (Math.pow(ym, one_minus_p)))/one_minus_p + Math.pow(ym, two_minus_p)/two_minus_p;
        default:
          throw new RuntimeException("unknown family " + _family);
      }
    }


    public final double link(double x) {
      switch(_link) {
        case identity:
          return x;
        case logit:
          assert 0 <= x && x <= 1:"x out of bounds, expected <0,1> range, got " + x;
          return Math.log(x / (1 - x));
        case log:
          return Math.log(x);
        case inverse:
          double xx = (x < 0) ? Math.min(-1e-5, x) : Math.max(1e-5, x);
          return 1.0 / xx;
        case tweedie:
          return Math.pow(x, _tweedie_link_power);
        default:
          throw new RuntimeException("unknown link function " + this);
      }
    }

    public final double linkDeriv(double x) {
      switch(_link) {
        case logit:
          double div = (x * (1 - x));
          if(div == 0) return 1e9; // avoid numerical instability
          return 1.0 / div;
        case identity:
          return 1;
        case log:
          return 1.0 / x;
        case inverse:
          return -1.0 / (x * x);
        case tweedie:
          return _tweedie_link_power * Math.pow(x, _tweedie_link_power - 1);
        default:
          throw H2O.unimpl();
      }
    }

    public final double linkInv(double x) {
      switch(_link) {
        case identity:
          return x;
        case logit:
          return 1.0 / (Math.exp(-x) + 1.0);
        case log:
          return Math.exp(x);
        case inverse:
          double xx = (x < 0) ? Math.min(-1e-5, x) : Math.max(1e-5, x);
          return 1.0 / xx;
        case tweedie:
          return Math.pow(x, 1/ _tweedie_link_power);
        default:
          throw new RuntimeException("unexpected link function id  " + this);
      }
    }

    public final double linkInvDeriv(double x) {
      switch(_link) {
        case identity:
          return 1;
        case logit:
          double g = Math.exp(-x);
          double gg = (g + 1) * (g + 1);
          return g / gg;
        case log:
          //return (x == 0)?MAX_SQRT:1/x;
          return Math.max(Math.exp(x), Double.MIN_NORMAL);
        case inverse:
          double xx = (x < 0) ? Math.min(-1e-5, x) : Math.max(1e-5, x);
          return -1 / (xx * xx);
        case tweedie:
          double vp = (1. - _tweedie_link_power) / _tweedie_link_power;
          return (1/ _tweedie_link_power) * Math.pow(x, vp);
        default:
          throw new RuntimeException("unexpected link function id  " + this);
      }
    }

    // supported families
    public enum Family {
      gaussian(Link.identity), binomial(Link.logit), poisson(Link.log),
      gamma(Link.inverse), tweedie(Link.tweedie);
      public final Link defaultLink;
      Family(Link link){defaultLink = link;}
    }
    public static enum Link {family_default, identity, logit, log,inverse,tweedie}

    public static enum Solver {ADMM, L_BFGS}

    // helper function
    static final double y_log_y(double y, double mu) {
      if(y == 0)return 0;
      if(mu < Double.MIN_NORMAL) mu = Double.MIN_NORMAL;
      return y * Math.log(y / mu);
    }

  }
  public static class GLM_LBFGS_Parameters extends GLMParameters {}

  public static class Submodel extends Iced {
    final double lambda_value;
    final int        iteration;
    final long       run_time;
//    GLMValidation training;    TODO this needs be taken care of for training set AND validation set
//    GLMValidation xtraining;
    GLMValidation validation;
    GLMValidation xvalidation;
    final int rank;
    final int [] idxs;
    final boolean sparseCoef;
    double []  beta;
    double []  norm_beta;

    public Submodel(double lambda , double [] beta, double [] norm_beta, long run_time, int iteration, boolean sparseCoef){
      this.lambda_value = lambda;
      this.run_time = run_time;
      this.iteration = iteration;
      int r = 0;
      if(beta != null){
        final double [] b = norm_beta != null?norm_beta:beta;
        // grab the indeces of non-zero coefficients
        for(double d:beta)if(d != 0)++r;
        idxs = MemoryManager.malloc4(sparseCoef?r:beta.length);
        int j = 0;
        for(int i = 0; i < beta.length; ++i)
          if(!sparseCoef || beta[i] != 0)idxs[j++] = i;
        j = 0;
        this.beta = MemoryManager.malloc8d(idxs.length);
        for(int i:idxs)
          this.beta[j++] = beta[i];
        if(norm_beta != null){
          j = 0;
          this.norm_beta = MemoryManager.malloc8d(idxs.length);
          for(int i:idxs) this.norm_beta[j++] = norm_beta[i];
        }
      } else idxs = null;
      rank = r;
      this.sparseCoef = sparseCoef;
    }
  }
  public static void setSubmodel(H2O.H2OCountedCompleter cmp, Key modelKey, final double lambda, double[] beta, double[] norm_beta, final int iteration, long runtime, boolean sparseCoef, final GLMValidation val){
    final Submodel sm = new Submodel(lambda,beta, norm_beta, runtime, iteration,sparseCoef);
    sm.validation = val;
    cmp.addToPendingCount(1);
    new TAtomic<GLMModel>(cmp){
      @Override
      public GLMModel atomic(GLMModel old) {
        if(old == null)return old; // job could've been cancelled!
        if(val != null)old._defaultThresholds = val.thresholds;
        if(old._output._submodels == null){
          old._output = (GLMOutput)old._output.clone();
          old._output._submodels = new Submodel[]{sm};
        } else {
          int id = old._output.submodelIdForLambda(lambda);
          if (id < 0) {
            id = -id - 1;
            Submodel [] sms = Arrays.copyOf(old._output._submodels, old._output._submodels.length + 1);
            for (int i = sms.length-1; i > id; --i)
              sms[i] = sms[i - 1];
            sms[id] = sm;
            old._output = (GLMOutput)old._output.clone();
            old._output._submodels = sms;
          } else {
            if (old._output._submodels[id].iteration < sm.iteration)
              old._output._submodels[id] = sm;
          }
        }
        old._output.pickBestModel(false);
        old._run_time = Math.max(old._run_time,sm.run_time);
        return old;
      }
    }.fork(modelKey);
  }

  public int rank(double lambda){return -1;}
  
  final double _lambda_max;
  final double _ymu;
  final long   _nobs;
  long   _run_time;
  
  public static class GLMOutput extends SupervisedModel.SupervisedOutput {
    Submodel [] _submodels;
    int         _best_lambda_idx;
    float       _threshold;
    double   [] _global_beta;
//    String   [] _coefficient_names;
    TwoDimTable _coefficients_table;
    TwoDimTable _coefficients_magnitude;
    double 		  _residual_deviance;
    double 		  _null_deviance;
    double 		  _residual_degrees_of_freedom;
    double		  _null_degrees_of_freedom;
    double      _aic;
    double      _auc;
    boolean _binomial;
    public int rank() {return rank(_submodels[_best_lambda_idx].lambda_value);}

    public GLMOutput() { }
    public GLMOutput(SupervisedModelBuilder b, DataInfo dinfo, boolean binomial){
      super(b);
      String [] cnames = dinfo.coefNames();
      String [] pnames = dinfo._adaptedFrame.names();
      String [] colTypes = new String[2];
      String [] colFormat = new String[2];
      Arrays.fill(colTypes, "double");
      Arrays.fill(colFormat, "%5f");
      String [] coefficient_names = Arrays.copyOf(cnames,cnames.length+1);
      coefficient_names[cnames.length] = "Intercept";
      _coefficients_table = new TwoDimTable(
              "Best Lambda",
              coefficient_names,
              new String []{"Coefficients", "Norm Coefficients"},
              colTypes,
              colFormat,
              "Column");
      _binomial = binomial;
    }

    @Override
    public int nclasses() {
      return _binomial?2:1;
    }
    private static String [] binomialClassNames = new String[]{"0","1"};
    @Override public String [] classNames(){
      return _binomial?binomialClassNames:null;
    }
    void addNullSubmodel(double lmax,double icept, GLMValidation val){
      assert _submodels == null;
      double [] beta = MemoryManager.malloc8d(_coefficients_table.getRowDim());
      beta[beta.length-1] = icept;
      _submodels = new Submodel[]{new Submodel(lmax,beta,beta,0,0,_coefficients_table.getRowDim() > 750)};
      _submodels[0].validation = val;
    }
    public int  submodelIdForLambda(double lambda){
      if(lambda >= _submodels[0].lambda_value) return 0;
      int i = _submodels.length-1;
      for(;i >=0; --i)
        // first condition to cover lambda == 0 case (0/0 is Inf in java!)
        if(lambda == _submodels[i].lambda_value || Math.abs(_submodels[i].lambda_value - lambda)/lambda < 1e-5)
          return i;
        else if(_submodels[i].lambda_value > lambda)
          return -i-2;
      return -1;
    }
    public Submodel  submodelForLambda(double lambda){
      return _submodels[submodelIdForLambda(lambda)];
    }
    public int rank(double lambda) {
      Submodel sm = submodelForLambda(lambda);
      if(sm == null)return 0;
      return submodelForLambda(lambda).rank;
    }
    public void pickBestModel(boolean useAuc){
      int bestId = _submodels.length-1;
      if(_submodels.length > 2) {
        boolean xval = false;
        GLMValidation bestVal = null;
        for(Submodel sm:_submodels) {
          if(sm.xvalidation != null) {
            xval = true;
            bestVal = sm.xvalidation;
          }
        }
        if(!xval) bestVal = _submodels[0].validation;
        for (int i = 1; i < _submodels.length; ++i) {
          GLMValidation val = xval ? _submodels[i].xvalidation : _submodels[i].validation;
          if (val == null || val == bestVal) continue;
          if ((useAuc && val.auc > bestVal.auc) || val.residual_deviance < bestVal.residual_deviance) {
            bestVal = val;
            bestId = i;
          }
        }
      }
      setSubmodelIdx(_best_lambda_idx = bestId);
    }
    public void setSubmodelIdx(int l){
      _best_lambda_idx = l;
      if (_submodels[l].validation == null) {
        _threshold = 0.5f;
        _residual_deviance = Double.NaN;
        _null_deviance = Double.NaN;
        _residual_degrees_of_freedom = Double.NaN;
        _null_degrees_of_freedom = Double.NaN;
        _aic = Double.NaN;
        _auc = Double.NaN;
      } else {
        _threshold = _submodels[l].validation.best_threshold;
        _residual_deviance = _submodels[l].validation.residualDeviance();
        _null_deviance = _submodels[l].validation.nullDeviance();
        _residual_degrees_of_freedom = _submodels[l].validation.resDOF();
        _null_degrees_of_freedom = _submodels[l].validation.nullDOF();
        _aic = _submodels[l].validation.aic();
        _auc = _submodels[l].validation.auc();
      }
      if(_global_beta == null) _global_beta = MemoryManager.malloc8d(this._coefficients_table.getRowDim());
      else Arrays.fill(_global_beta,0);

      int j = 0;
      for(int i:_submodels[l].idxs) {
        _global_beta[i] = _submodels[l].beta[j];
        _coefficients_table.set(i, 0, _submodels[l].beta[j]);
        if(_submodels[l].norm_beta != null)
          _coefficients_table.set(i, 1, _submodels[l].norm_beta[j++]);
        else
          j++;
      }

      if(_submodels[l].norm_beta == null)
        _coefficients_magnitude = null;
      else {
        j = 0;
        String[] coef_names = new String[_coefficients_table.getRowDim()-1];
        double[] coef_scaled = new double[_coefficients_table.getRowDim()-1];
        for(int i = 0; i < _submodels[l].idxs.length-1; i++) {
          coef_names[j] = _coefficients_table.getRowHeaders()[j];
          coef_scaled[_submodels[l].idxs[i]] = Math.abs(_submodels[l].norm_beta[j++]);
        }
        _coefficients_magnitude = ModelMetrics.calcVarImp(coef_scaled, coef_names,
                "Normalized Coefficient Magnitudes", new String[] { "Magnitude", "Scaled", "Percentage" });
      }
    }
  }

  public static void setXvalidation(H2OCountedCompleter cmp, Key modelKey, final double lambda, final GLMValidation val){
    // expected cmp has already set correct pending count
    new TAtomic<GLMModel>(cmp){
      @Override
      public GLMModel atomic(GLMModel old) {
        if(old == null)return old; // job could've been cancelled
        old._output._submodels = old._output._submodels.clone();
        int id = old._output.submodelIdForLambda(lambda);
        old._output._submodels[id] = (Submodel)old._output._submodels[id].clone();
        old._output._submodels[id].xvalidation = val;
        old._output.pickBestModel(false);
        return old;
      }
    }.fork(modelKey);
  }
  /**
   * get beta coefficients in a map indexed by name
   * @return the estimated coefficients
   */
  public HashMap<String,Double> coefficients(){
    HashMap<String, Double> res = new HashMap<String, Double>();
    final double [] b = beta();
    if(b != null) for(int i = 0; i < b.length; ++i)res.put(_output._coefficients_table.getRowHeaders()[i],b[i]);
    return res;
  }

  static class FinalizeAndUnlockTsk extends DKeyTask<FinalizeAndUnlockTsk,GLMModel> {
    final Key _jobKey;
    public FinalizeAndUnlockTsk(H2OCountedCompleter cmp, Key modelKey, Key jobKey){
      super(cmp, modelKey);
      _jobKey = jobKey;
    }
    @Override
    protected void map(GLMModel glmModel) {
      glmModel._output.pickBestModel(false);
      glmModel.update(_jobKey);
      glmModel.unlock(_jobKey);
    }
  }
}
