#'
#' H2O Model Related Functions
#'

# ------------------------------- Helper Functions --------------------------- #
# Used to verify data, x, y and turn into the appropriate things
.verify_dataxy <- function(data, x, y, autoencoder = FALSE) {
  if(!is(data,  "H2OFrame"))
    stop('`data` must be an H2OFrame object')
  if(!is.character(x) && !is.numeric(x))
    stop('`x` must be column names or indices')
  if(!is.character(y) && !is.numeric(y))
    stop('`y` must be a column name or index')

  cc <- colnames(data)

  if(is.character(x)) {
    if(!all(x %in% cc))
      stop("Invalid column names: ", paste(x[!(x %in% cc)], collapse=','))
    x_i <- match(x, cc)
  } else {
    if(any( x < 1L | x > length(cc)))
      stop('out of range explanatory variable ', paste(x[x < 1L | x > length(cc)], collapse=','))
    x_i <- x
    x <- cc[x_i]
  }

  if(is.character(y)){
    if(!(y %in% cc))
      stop(y, ' is not a column name')
    y_i <- which(y == cc)
  } else {
    if(y < 1L || y > length(cc))
      stop('response variable index ', y, ' is out of range')
    y_i <- y
    y <- cc[y]
  }

  if(!autoencoder && (y %in% x)) {
    warning('removing response variable from the explanatory variables')
    x <- setdiff(x,y)
  }

  x_ignore <- setdiff(setdiff(cc, x), y)
  if(length(x_ignore) == 0L) x_ignore <- ''
  list(x=x, y=y, x_i=x_i, x_ignore=x_ignore, y_i=y_i)
}

.verify_datacols <- function(data, cols) {
  if(!is(data, "H2OFrame"))
    stop('`data` must be an H2OFrame object')
  if(!is.character(cols) && !is.numeric(cols))
    stop('`cols` must be column names or indices')

  cc <- colnames(data)
  if(length(cols) == 1L && cols == '')
    cols <- cc
  if(is.character(cols)) {
    if(!all(cols %in% cc))
      stop("Invalid column names: ", paste(cols[which(!cols %in% cc)], collapse=", "))
    cols_ind <- match(cols, cc)
  } else {
    if(any(cols < 1L | cols > length(cc)))
      stop('out of range explanatory variable ', paste(cols[cols < 1L | cols > length(cc)], collapse=','))
    cols_ind <- cols
    cols <- cc[cols_ind]
  }

  cols_ignore <- setdiff(cc, cols)
  if( length(cols_ignore) == 0L )
    cols_ignore <- ''
  list(cols=cols, cols_ind=cols_ind, cols_ignore=cols_ignore)
}

.build_cm <- function(cm, actual_names = NULL, predict_names = actual_names, transpose = TRUE) {
  categories <- length(cm)
  cf_matrix <- matrix(unlist(cm), nrow=categories)
  if(transpose)
    cf_matrix <- t(cf_matrix)

  cf_total <- apply(cf_matrix, 2L, sum)
  cf_error <- c(1 - diag(cf_matrix)/apply(cf_matrix,1L,sum), 1 - sum(diag(cf_matrix))/sum(cf_matrix))
  cf_matrix <- rbind(cf_matrix, cf_total)
  cf_matrix <- cbind(cf_matrix, round(cf_error, 3L))

  if(!is.null(actual_names))
    dimnames(cf_matrix) = list(Actual = c(actual_names, "Totals"), Predicted = c(predict_names, "Error"))
  cf_matrix
}




.h2o.startModelJob <- function(conn = h2o.getConnection(), algo, params, envir) {
  .key.validate(params$key)
  #---------- Force evaluate temporary ASTs ----------#
  ALL_PARAMS <- .h2o.__remoteSend(conn, method = "GET", .h2o.__MODEL_BUILDERS(algo))$model_builders[[algo]]$parameters

  params <- lapply(as.list(params), function(i) {
                     if (is.name(i))    i <- get(deparse(i), envir)
                     if (is.call(i))    i <- eval(i, envir)
                     if (is.integer(i)) i <- as.numeric(i)
                     i
                   })

  #---------- Check user parameter types ----------#
  error <- lapply(ALL_PARAMS, function(i) {
    e <- ""
    if (i$required && !(i$name %in% names(params)))
      e <- paste0("argument \"", i$name, "\" is missing, with no default\n")
    else if (i$name %in% names(params)) {
      # changing Java types to R types
      mapping <- .type.map[i$type,]
      type    <- mapping[1L, 1L]
      scalar  <- mapping[1L, 2L]
      if (is.na(type))
        stop("Cannot find type ", i$type, " in .type.map")
      if (scalar) { # scalar == TRUE
        if (type == "H2OModel")
            type <-  "character"
        if (!inherits(params[[i$name]], type))
          e <- paste0("\"", i$name , "\" must be of type ", type, ", but got ", class(params[[i$name]]), ".\n")
        else if ((length(i$values) > 1L) && !(params[[i$name]] %in% i$values)) {
          e <- paste0("\"", i$name,"\" must be in")
          for (fact in i$values)
            e <- paste0(e, " \"", fact, "\",")
          e <- paste(e, "but got", params[[i$name]])
        }
        if (inherits(params[[i$name]], 'numeric') && params[[i$name]] ==  Inf)
          params[[i$name]] <<- "Infinity"
        else if (inherits(params[[i$name]], 'numeric') && params[[i$name]] == -Inf)
          params[[i$name]] <<- "-Infinity"
      } else {      # scalar == FALSE
        k = which(params[[i$name]] == Inf | params[[i$name]] == -Inf)
        if (length(k) > 0)
          for (n in k)
            if (params[[i$name]][n] == Inf)
              params[[i$name]][n] <<- "Infinity"
            else
              params[[i$name]][n] <<- "-Infinity"
        if (!inherits(params[[i$name]], type))
          e <- paste0("vector of ", i$name, " must be of type ", type, ", but got ", class(params[[i$name]]), ".\n")
        else if (type == "character")
          params[[i$name]] <<- .collapse.char(params[[i$name]])
        else
          params[[i$name]] <<- .collapse(params[[i$name]])
      }
    }
    e
  })

  if(any(nzchar(error)))
    stop(error)

  #---------- Create parameter list to pass ----------#
  param_values <- lapply(params, function(i) {
    if(is(i, "H2OFrame"))
      i@key
    else
      i
  })

  #---------- Validate parameters ----------#
  validation <- .h2o.__remoteSend(conn, method = "POST", paste0(.h2o.__MODEL_BUILDERS(algo), "/parameters"), .params = param_values)
  if(length(validation$validation_messages) != 0L) {
    error <- lapply(validation$validation_messages, function(i) {
      if( i$message_type == "ERROR" )
        paste0(i$message, ".\n")
      else ""
    })
    if(any(nzchar(error))) stop(error)
    warn <- lapply(validation$validation_messages, function(i) {
      if( i$message_type == "WARN" )
        paste0(i$message, ".\n")
      else ""
    })
    if(any(nzchar(warn))) warning(warn)
  }

  #---------- Build! ----------#
  res <- .h2o.__remoteSend(conn, method = "POST", .h2o.__MODEL_BUILDERS(algo), .params = param_values)

  job_key  <- res$job$key$name
  dest_key <- res$job$dest$name

  new("H2OModelFuture",h2o=conn, job_key=job_key, destination_key=dest_key)
}

.h2o.createModel <- function(conn = h2o.getConnection(), algo, params, envir) {
 params$training_frame <- get("training_frame", parent.frame())
 delete_train <- !.is.eval(params$training_frame)
 if (delete_train) {
    temp_train_key <- params$training_frame@key
    .h2o.eval.frame(conn = conn, ast = params$training_frame@mutable$ast, key = temp_train_key)
 }
 if (!is.null(params$validation_frame)){
    params$validation_frame <- get("validation_frame", parent.frame())
    delete_valid <- !.is.eval(params$validation_frame)
    if (delete_valid) {
      temp_valid_key <- params$validation_frame@key
      .h2o.eval.frame(conn = conn, ast = params$validation_frame@mutable$ast, key = temp_valid_key)
    }
  }
  m = h2o.getFutureModel(.h2o.startModelJob(conn, algo, params, envir))
  if (delete_train)
    h2o.rm(temp_train_key)
  if (!is.null(params$validation_frame))
    if (delete_valid)
      h2o.rm(temp_valid_key)
  m
}

predict.H2OModel <- function(object, newdata, ...) {
  if (missing(newdata)) {
    stop("predictions with a missing `newdata` argument is not implemented yet")
  }

  tmp_data <- !.is.eval(newdata)
  if( tmp_data ) {
    key  <- newdata@key
    .h2o.eval.frame(conn=h2o.getConnection(), ast=newdata@mutable$ast, key=key)
  }

  # Send keys to create predictions
  url <- paste0('Predictions.json/models/', object@key, '/frames/', newdata@key)
  res <- .h2o.__remoteSend(object@conn, url, method = "POST")
  res <- res$model_metrics[[1L]]$predictions

  # Grab info to make data frame
  .h2o.parsedPredData(newdata@conn, res)
}

#' Cross Validate an H2O Model
h2o.crossValidate <- function(model, nfolds, model.type = c("gbm", "glm", "deeplearning"), params, strategy = c("mod1", "random"), ...)
{
  output <- data.frame()
  dots <- list(...)

  for(type in dots)
    if (is.environment(type))
    {
      dots$envir <- type
      type <- NULL
    }
  if (is.null(dots$envir))
    dots$envir <- parent.frame()
#   params$envir <- l$envir

  if( nfolds < 2 ) stop("`nfolds` must be greater than or equal to 2")
  if( missing(model) & missing(model.type) ) stop("must declare `model` or `model.type`")
  else if( missing(model) )
  {
    if(model.type == "gbm") model.type = "h2o.gbm"
    else if(model.type == "glm") model.type = "h2o.glm"
    else if(model.type == "deeplearning") model.type = "h2o.deeplearning"

    model <- do.call(model.type, c(params, envir = dots$envir))
  }
  output[1, "fold_num"] <- -1
  output[1, "model_key"] <- model@key
  # output[1, "model"] <- model@model$mse_valid

  data <- params$training_frame
  data <- eval(data, dots$envir)
  data.len <- nrow(data)

  # nfold_vec <- h2o.sample(fr, 1:nfolds)
  nfold_vec <- sample(rep(1:nfolds, length.out = data.len), data.len)

  fnum_id <- as.h2o(nfold_vec, model@conn)
  fnum_id <- h2o.cbind(fnum_id, data)

  xval <- lapply(1:nfolds, function(i) {
      params$training_frame <- data[fnum_id$object != i, ]
      params$validation_frame <- data[fnum_id$object != i, ]
      fold <- do.call(model.type, c(params, envir = dots$envir))
      output[(i+1), "fold_num"] <<- i - 1
      output[(i+1), "model_key"] <<- fold@key
      # output[(i+1), "cv_err"] <<- mean(as.vector(fold@model$mse_valid))
      fold
    })
  print(output)

  model
}

#' Model Performance Metrics in H2O
#'
#' Given a trained h2o model, compute its performance on the given
#' dataset
#'
#'
#' @param model An \linkS4class{H2OModel} object
#' @param data An \linkS4class{H2OFrame}. The model will make predictions
#'        on this dataset, and subsequently score them. The dataset should
#'        match the dataset that was used to train the model, in terms of
#'        column names, types, and dimensions.
#' @return Returns an object of the \linkS4class{H2OModelMetrics} subclass.
#' @examples
#' library(h2o)
#' localH2O <- h2o.init()
#' prosPath <- system.file("extdata", "prostate.csv", package="h2o")
#' prostate.hex <- h2o.uploadFile(localH2O, path = prosPath)
#' prostate.hex$CAPSULE <- as.factor(prostate.hex$CAPSULE)
#' prostate.gbm <- h2o.gbm(3:9, "CAPSULE", prostate.hex)
#' h2o.performance(model = prostate.gbm, data=prostate.hex)
h2o.performance <- function(model, data=NULL) {
  # Some parameter checking
  if(!is(model, "H2OModel")) stop("`model` must an H2OModel object")
  if(!is.null(data) && !is(data, "H2OFrame")) stop("`data` must be an H2OFrame object")

  parms <- list()
  parms[["model"]] <- model@key
  if(!is.null(data))
    parms[["frame"]] <- data@key

  if(missing(data)){
    res <- .h2o.__remoteSend(model@conn, method = "GET", .h2o.__MODEL_METRICS(model@key))
  }
  else {
    res <- .h2o.__remoteSend(model@conn, method = "POST", .h2o.__MODEL_METRICS(model@key,data@key), .params = parms)
  }

  algo <- model@algorithm
  res$model_metrics <- res$model_metrics[[1L]]
  metrics <- res$model_metrics[!(names(res$model_metrics) %in% c("__meta", "names", "domains", "model_category"))]

  model_category <- res$model_metrics$model_category
  Class <- paste0("H2O", model_category, "Metrics")

  new(Class     = Class,
      algorithm = algo,
      metrics   = metrics)
}

#' Retrieve an H2O AUC metric
#'
#' Retrieves the AUC value from an \linkS4class{H2OBinomialMetrics}.
#'
#' @param object An \linkS4class{H2OBinomialMetrics} object.
#' @seealso \code{\link{h2o.giniCoef}} for the GINI coefficient,
#'          \code{\link{h2o.mse}} for MSE, and \code{\link{h2o.metric}} for the
#'          various threshold metrics. See \code{\link{h2o.performance}} for
#'          creating H2OModelMetrics objects.
#' @examples
#' library(h2o)
#' h2o.init()
#'
#' prosPath <- system.file("extdata", "prostate.csv", package="h2o")
#' hex <- h2o.uploadFile(prosPath)
#'
#' hex[,2] <- as.factor(hex[,2])
#' model <- h2o.gbm(x = 3:9, y = 2, training_frame = hex, loss = "bernoulli")
#' perf <- h2o.performance(model, hex)
#' h2o.auc(perf)
h2o.auc <- function(object) {
  if(is(object, "H2OBinomialMetrics")){
    object@metrics$AUC
  }
  else{
    stop(paste0("No AUC for ",class(object)))
  }
}

#' Retrieve the GINI Coefficcient
#'
#' Retrieves the GINI coefficient from an \linkS4class{H2OBinomialMetrics}.
#'
#' @param object an \linkS4class{H2OBinomialMetrics} object.
#' @seealso \code{\link{h2o.auc}} for AUC,  \code{\link{h2o.giniCoef}} for the
#'          GINI coefficient, and \code{\link{h2o.metric}} for the various. See
#'          \code{\link{h2o.performance}} for creating H2OModelMetrics objects.
#'          threshold metrics.
#' @examples
#' library(h2o)
#' h2o.init()
#'
#' prosPath <- system.file("extdata", "prostate.csv", package="h2o")
#' hex <- h2o.uploadFile(prosPath)
#'
#' hex[,2] <- as.factor(hex[,2])
#' model <- h2o.gbm(x = 3:9, y = 2, training_frame = hex, loss = "bernoulli")
#' perf <- h2o.performance(model, hex)
#' h2o.giniCoef(perf)
h2o.giniCoef <- function(object) {
  if(is(object, "H2OBinomialMetrics")){
    object@metrics$Gini
  }
  else{
    stop(paste0("No Gini for ",class(object)))
  }
}
#' Retrieves Mean Squared Error Value
#'
#' Retrieves the mean squared error value from an \linkS4class{H2OModelMetrics}
#' object.
#'
#' This function only supports \linkS4class{H2OBinomialMetrics},
#' \linkS4class{H2OMultinomialMetrics}, and \linkS4class{H2ORegressionMetrics} objects.
#'
#' @param object An \linkS4class{H2OModelMetrics} object of the correct type.
#' @seealso \code{\link{h2o.auc}} for AUC, \code{\link{h2o.mse}} for MSE, and
#'          \code{\link{h2o.metric}} for the various threshold metrics. See
#'          \code{\link{h2o.performance}} for creating H2OModelMetrics objects.
#' @examples
#' library(h2o)
#' h2o.init()
#'
#' prosPath <- system.file("extdata", "prostate.csv", package="h2o")
#' hex <- h2o.uploadFile(prosPath)
#'
#' hex[,2] <- as.factor(hex[,2])
#' model <- h2o.gbm(x = 3:9, y = 2, training_frame = hex, loss = "bernoulli")
#' perf <- h2o.performance(model, hex)
#' h2o.mse(perf)
h2o.mse <- function(object) {
  if(is(object, "H2OBinomialMetrics") || is(object, "H2OMultinomialMetrics") || is(object, "H2ORegressionMetrics")){
    object@metrics$mse
  }
  else{
    stop(paste0("No MSE for ",class(object)))
  }
}


#' Retrieve the Log Loss Value
#'
#' Retrieves the log loss output for a \linkS4class{H2OBinomialMetrics} or
#' \linkS4class{H2OMultinomialMetrics} object
#'
#' @param object a \linkS4class{H2OModelMetrics} object of the correct type.
h2o.logloss <- function(object) {
  if(is(object, "H2OBinomialMetrics") || is(object, "H2OMultinomialMetrics"))
    object@metrics$logloss
  else
    stop(paste("No log loss for",class(object)))
}

#' H2O Model Metric Accessor Functions
#'
#' A series of functions that retrieve model metric details.
#'
#' Many of these functions have an optional thresholds parameter. Currently
#' only increments of 0.1 are allowed. If not specified, the functions will
#' return all possible values. Otherwise, the function will return the value for
#' the indicated threshold.
#'
#' Currently, the these functions are only supported by
#' \linkS4class{H2OBinomialMetrics} objects.
#'
#' @param object An \linkS4class{H2OModelMetrics} object of the correct type.
#' @param thresholds A value or a list of values between 0.0 and 1.0.
#' @param metric A specified paramter to retrieve.
#' @return Returns either a single value, or a list of values.
#' @seealso \code{\link{h2o.auc}} for AUC, \code{\link{h2o.giniCoef}} for the
#'          GINI coefficient, and \code{\link{h2o.mse}} for MSE. See
#'          \code{\link{h2o.performance}} for creating H2OModelMetrics objects.
#' @examples
#' library(h2o)
#' h2o.init()
#'
#' prosPath <- system.file("extdata", "prostate.csv", package="h2o")
#' hex <- h2o.uploadFile(prosPath)
#'
#' hex[,2] <- as.factor(hex[,2])
#' model <- h2o.gbm(x = 3:9, y = 2, training_frame = hex, loss = "bernoulli")
#' perf <- h2o.performance(model, hex)
#' h2o.F1(perf, c(0.3,0.4,0.5,0.6))
h2o.metric <- function(object, thresholds, metric) {
  if(is(object, "H2OBinomialMetrics")){
    if(!missing(thresholds)) {
      t <- as.character(thresholds)
      t[t=="0"] <- "0.0"
      t[t=="1"] <- "1.0"
      if(!all(t %in% rownames(object@metrics$thresholds_and_metric_scores))) {
        stop(paste0("User-provided thresholds: ", paste(t,collapse=', '), ", are not a subset of the available thresholds: ", paste(rownames(object@metrics$thresholds_and_metric_scores), collapse=', ')))
      }
      else {
        output <- object@metrics$thresholds_and_metric_scores[t, metric]
        names(output) <- t
        output
      }
    }
    else {
      output <- object@metrics$thresholds_and_metric_scores[, metric]
      names(output) <- rownames(object@metrics$thresholds_and_metric_scores)
      output
    }
  }
  else{
    stop(paste0("No ", metric, " for ",class(object)))
  }
}

#' @rdname h2o.metric
h2o.F0point5 <- function(object, thresholds){
  h2o.metric(object, thresholds, "F0point5")
}

#' @rdname h2o.metric
h2o.F1 <- function(object, thresholds){
  h2o.metric(object, thresholds, "F1")
}

#' @rdname h2o.metric
h2o.F2 <- function(object, thresholds){
  h2o.metric(object, thresholds, "F2")
}

#' @rdname h2o.metric
h2o.accuracy <- function(object, thresholds){
  h2o.metric(object, thresholds, "accuracy")
}

#' @rdname h2o.metric
h2o.error <- function(object, thresholds){
  h2o.metric(object, thresholds, "error")
}

#' @rdname h2o.metric
h2o.maxPerClassError <- function(object, thresholds){
  h2o.metric(object, thresholds, "max_per_class_error")
}

#' @rdname h2o.metric
h2o.mcc <- function(object, thresholds){
  h2o.metric(object, thresholds, "mcc")
}

#' @rdname h2o.metric
h2o.precision <- function(object, thresholds){
  h2o.metric(object, thresholds, "precision")
}

#' @rdname h2o.metric
h2o.recall <- function(object, thresholds){
  h2o.metric(object, thresholds, "recall")
}

#' @rdname h2o.metric
h2o.specificity <- function(object, thresholds){
  h2o.metric(object, thresholds, "specificity")
}


#' Access H2O Confusion Matrices
#'
#' Retrieve either a single or many confusion matrices from H2O objects.
#'
#' The \linkS4class{H2OModelMetrics} version of this function will only take
#' \linkS4class{H2OBinomialMetrics} or \linkS4class{H2OMultinomialMetrics}
#' objects. If no threshold is specified, all possible thresholds are selected.
#'
#' @param object Either an \linkS4class{H2OModel} object or an
#'        \linkS4class{H2OModelMetrics} object.
#' @param newdata An \linkS4class{H2OFrame} object that can be scored on.
#'        Requires a valid response column.
#' @param thresholds (Optional) A value or a list of values between 0.0 and 1.0.
#'        This value is only used in the case of
#'        \linkS4class{H2OBinomialMetrics} objects.
#' @return Calling this function on \linkS4class{H2OModel} objects returns a
#'         confusion matrix corresponding to the \code{\link{predict}} function.
#'         If used on an \linkS4class{H2OBinomialMetrics} object, returns a list
#'         of matrices corresponding to the number of thresholds specified.
#' @seealso \code{\link{predict}} for generating prediction frames,
#'          \code{\link{h2o.performance}} for creating
#'          \linkS4class{H2OModelMetrics}.
#' @examples
#' library(h2o)
#' h2o.init()
#' prosPath <- system.file("extdata", "prostate.csv", package="h2o")
#' hex <- h2o.uploadFile(prosPath)
#' hex[,2] <- as.factor(hex[,2])
#' model <- h2o.gbm(x = 3:9, y = 2, training_frame = hex, loss = "bernoulli")
#' h2o.confusionMatrix(model, hex)
#' # Generating a ModelMetrics object
#' perf <- h2o.performance(model, hex)
#' # 0.46 is the F1 maximum threshold, used as the default prediction threshold
#' h2o.confusionMatrix(perf, 0.46)
setGeneric("h2o.confusionMatrix", function(object, ...) {})

#' @rdname h2o.confusionMatrix
setMethod("h2o.confusionMatrix", "H2OModel", function(object, newdata) {
  delete <- !.is.eval(newdata)
  if(delete) {
    temp_key <- newdata@key
    .h2o.eval.frame(conn = newdata@conn, ast = newdata@mutable$ast, key = temp_key)
  }

  url <- paste0("Predictions.json/models/",object@key, "/frames/", newdata@key)
  res <- .h2o.__remoteSend(object@conn, url, method="POST")

  if(delete)
    h2o.rm(temp_key)

  res$model_metrics[[1L]]$cm$table
})

#' @rdname h2o.confusionMatrix
setMethod("h2o.confusionMatrix", "H2OModelMetrics", function(object, thresholds) {
  if(is(object, "H2OBinomialMetrics")){
    names(object@metrics$confusion_matrices) <- rownames(object@metrics$thresholds_and_metric_scores)
    if(!missing(thresholds)) {
      t <- as.character(thresholds)
      t[t=="0"] <- "0.0"
      t[t=="1"] <- "1.0"
      if(!all(t %in% rownames(object@metrics$thresholds_and_metric_scores))) {
        stop(paste0("User-provided thresholds: ", paste(t,collapse=', '), ", are not a subset of the available thresholds: ", paste(rownames(object@metrics$thresholds_and_metric_scores), collapse=', ')))
      }
      else {
        object@metrics$confusion_matrices[t]
      }
    }
    else {
        object@metrics$confusion_matrices
    }
  } else if(is(object, "H2OMultinomialMetrics")) {
    object@metrics$cm$table
  }
  else{
    stop(paste0("No Confusion Matrices for ",class(object)))
  }
})

plot.H2OBinomialMetrics <- function(object, type = "roc", ...) {
  # TODO: add more types (i.e. cutoffs)
  if(!type %in% c("roc")) stop("type must be 'roc'")
  if(type == "roc") {
    xaxis = "False Positive Rate"; yaxis = "True Positive Rate"
    plot(1 - object@metrics$thresholds_and_metric_scores$specificity, object@metrics$thresholds_and_metric_scores$recall, main = paste(yaxis, "vs", xaxis), xlab = xaxis, ylab = yaxis, ...)
    abline(0, 1, lty = 2)
  }
}
