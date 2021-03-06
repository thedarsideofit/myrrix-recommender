/*
 * Copyright Myrrix Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.myrrix.common.math;

import java.lang.reflect.Field;
import java.util.Arrays;

import com.google.common.base.Preconditions;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;

import net.myrrix.common.ClassUtils;
import net.myrrix.common.collection.FastByIDFloatMap;
import net.myrrix.common.collection.FastByIDMap;
import net.myrrix.common.collection.FastIDSet;

/**
 * Contains utility methods for dealing with matrices, which are here represented as
 * {@link FastByIDMap}s of {@link FastByIDFloatMap}s, or of {@code float[]}.
 *
 * @author Sean Owen
 * @since 1.0
 */
public final class MatrixUtils {

  private static final int PRINT_COLUMN_WIDTH = 12;
  // This hack saves a lot of time spent copying out data from Array2DRowRealMatrix objects
  private static final Field MATRIX_DATA_FIELD;
  private static final LinearSystemSolver MATRIX_INVERTER;
  static {
    MATRIX_DATA_FIELD = ClassUtils.loadField(Array2DRowRealMatrix.class, "data");
    String lssClassName = Boolean.parseBoolean(System.getProperty("common.matrix.nativeMath", "false")) ?
        "net.myrrix.common.math.JBlasLinearSystemSolver" : "net.myrrix.common.math.CommonsMathLinearSystemSolver";
    MATRIX_INVERTER = ClassUtils.loadInstanceOf(lssClassName, LinearSystemSolver.class);
  }

  private MatrixUtils() {
  }

  /**
   * Efficiently increments an entry in two parallel, sparse matrices.
   *
   * @param row row to increment
   * @param column column to increment
   * @param value increment value
   * @param RbyRow matrix R to update, keyed by row
   * @param RbyColumn matrix R to update, keyed by column
   */
  public static void addTo(long row,
                           long column,
                           float value,
                           FastByIDMap<FastByIDFloatMap> RbyRow,
                           FastByIDMap<FastByIDFloatMap> RbyColumn) {
    addToByRow(row, column, value, RbyRow);
    addToByRow(column, row, value, RbyColumn);
  }

  /**
   * Efficiently increments an entry in a row-major sparse matrix.
   *
   * @param row row to increment
   * @param column column to increment
   * @param value increment value
   * @param RbyRow matrix R to update, keyed by row
   */
  private static void addToByRow(long row,
                                 long column,
                                 float value,
                                 FastByIDMap<FastByIDFloatMap> RbyRow) {

    FastByIDFloatMap theRow = RbyRow.get(row);
    if (theRow == null) {
      theRow = new FastByIDFloatMap();
      RbyRow.put(row, theRow);
    }
    theRow.increment(column, value);
  }

  /**
   * Efficiently removes an entry in two parallel, sparse matrices.
   *
   * @param row row to remove
   * @param column column to remove
   * @param RbyRow matrix R to update, keyed by row
   * @param RbyColumn matrix R to update, keyed by column
   */
  public static void remove(long row,
                            long column,
                            FastByIDMap<FastByIDFloatMap> RbyRow,
                            FastByIDMap<FastByIDFloatMap> RbyColumn) {
    removeByRow(row, column, RbyRow);
    removeByRow(column, row, RbyColumn);
  }
  
  /**
   * Efficiently removes an entry from a row-major sparse matrix.
   *
   * @param row row to remove
   * @param column column to remove
   * @param RbyRow matrix R to update, keyed by row
   */
  private static void removeByRow(long row, long column, FastByIDMap<FastByIDFloatMap> RbyRow) {
    FastByIDFloatMap theRow = RbyRow.get(row);
    if (theRow != null) {
      theRow.remove(column);
      if (theRow.isEmpty()) {
        RbyRow.remove(row);
      }
    }
  }

  /**
   * @return {@link LinearSystemSolver#isNonSingular(RealMatrix)}
   */
  public static boolean isNonSingular(RealMatrix M) {
    return MATRIX_INVERTER.isNonSingular(M);    
  }

  /**
   * @return {@link LinearSystemSolver#getSolver(RealMatrix)}
   */
  public static Solver getSolver(RealMatrix M) {
    return MATRIX_INVERTER.getSolver(M);
  }

  /**
   * @param M small {@link RealMatrix}
   * @param S wide, short matrix
   * @return M * S as a newly allocated matrix
   */
  public static FastByIDMap<float[]> multiply(RealMatrix M, FastByIDMap<float[]> S) {
    FastByIDMap<float[]> result = new FastByIDMap<float[]>(S.size());
    double[][] matrixData = accessMatrixDataDirectly(M);
    for (FastByIDMap.MapEntry<float[]> entry : S.entrySet()) {
      result.put(entry.getKey(), matrixMultiply(matrixData, entry.getValue()));
    }
    return result;
  }

  public static RealMatrix multiplyXYT(FastByIDMap<float[]> X, FastByIDMap<float[]> Y) {
    int Ysize = Y.size();
    int Xsize = X.size();
    RealMatrix result = new Array2DRowRealMatrix(Xsize, Ysize);
    for (int row = 0; row < Xsize; row++) {
      for (int col = 0; col < Ysize; col++) {
        result.setEntry(row, col, SimpleVectorMath.dot(X.get(row), Y.get(col)));
      }
    }
    return result;
  }

  /**
   * @param matrix an {@link Array2DRowRealMatrix}
   * @return its "data" field -- not a copy
   */
  public static double[][] accessMatrixDataDirectly(RealMatrix matrix) {
    try {
      return (double[][]) MATRIX_DATA_FIELD.get(matrix);
    } catch (IllegalAccessException iae) {
      throw new IllegalStateException(iae);
    }
  }

  public static double[] multiply(RealMatrix matrix, float[] V) {
    double[][] M = accessMatrixDataDirectly(matrix);
    int rows = M.length;
    int cols = V.length;
    double[] out = new double[rows];
    for (int i = 0; i < rows; i++) {
      double total = 0.0;
      double[] matrixRow = M[i];
      for (int j = 0; j < cols; j++) {
        total += V[j] * matrixRow[j];
      }
      out[i] = total;
    }
    return out;
  }

  /**
   * @param M matrix
   * @param V column vector
   * @return column vector M * V
   */
  private static float[] matrixMultiply(double[][] M, float[] V) {
    int rows = M.length;
    int cols = V.length;
    float[] out = new float[rows];
    for (int i = 0; i < rows; i++) {
      double total = 0.0;
      double[] matrixRow = M[i];
      for (int j = 0; j < cols; j++) {
        total += V[j] * matrixRow[j];
      }
      out[i] = (float) total;
    }
    return out;
  }

  /**
   * @param M tall, skinny matrix
   * @return MT * M as a dense matrix
   */
  public static RealMatrix transposeTimesSelf(FastByIDMap<float[]> M) {
    if (M == null || M.isEmpty()) {
      return null;
    }
    RealMatrix result = null;
    for (FastByIDMap.MapEntry<float[]> entry : M.entrySet()) {
      float[] vector = entry.getValue();
      int dimension = vector.length;
      if (result == null) {
        result = new Array2DRowRealMatrix(dimension, dimension);
      }
      for (int row = 0; row < dimension; row++) {
        float rowValue = vector[row];
        for (int col = 0; col < dimension; col++) {
          result.addToEntry(row, col, rowValue * vector[col]);
        }
      }
    }
    Preconditions.checkNotNull(result);
    return result;
  }

  /**
   * @param M matrix to print
   * @return a print-friendly rendering of a sparse matrix. Not useful for wide matrices.
   */
  public static String matrixToString(FastByIDMap<FastByIDFloatMap> M) {
    StringBuilder result = new StringBuilder();
    long[] colKeys = unionColumnKeysInOrder(M);
    appendWithPadOrTruncate("", result);
    for (long colKey : colKeys) {
      result.append('\t');
      appendWithPadOrTruncate(colKey, result);
    }
    result.append("\n\n");
    long[] rowKeys = keysInOrder(M);
    for (long rowKey : rowKeys) {
      appendWithPadOrTruncate(rowKey, result);
      FastByIDFloatMap row = M.get(rowKey);
      for (long colKey : colKeys) {
        result.append('\t');
        float value = row.get(colKey);
        if (Float.isNaN(value)) {
          appendWithPadOrTruncate("", result);
        } else {
          appendWithPadOrTruncate(value, result);
        }
      }
      result.append('\n');
    }
    result.append('\n');
    return result.toString();
  }

  private static long[] keysInOrder(FastByIDMap<?> map) {
    FastIDSet keys = new FastIDSet(map.size());
    LongPrimitiveIterator it = map.keySetIterator();
    while (it.hasNext()) {
      keys.add(it.nextLong());
    }
    long[] keysArray = keys.toArray();
    Arrays.sort(keysArray);
    return keysArray;
  }

  private static long[] unionColumnKeysInOrder(FastByIDMap<FastByIDFloatMap> M) {
    FastIDSet keys = new FastIDSet(1000);
    for (FastByIDMap.MapEntry<FastByIDFloatMap> entry : M.entrySet()) {
      LongPrimitiveIterator it = entry.getValue().keySetIterator();
      while (it.hasNext()) {
        keys.add(it.nextLong());
      }
    }
    long[] keysArray = keys.toArray();
    Arrays.sort(keysArray);
    return keysArray;
  }

  private static void appendWithPadOrTruncate(long value, StringBuilder to) {
    appendWithPadOrTruncate(Long.toString(value), to);
  }

  private static void appendWithPadOrTruncate(float value, StringBuilder to) {
    String stringValue = Float.toString(value);
    if (value >= 0.0f) {
      stringValue = ' ' + stringValue;
    }
    appendWithPadOrTruncate(stringValue, to);
  }

  private static void appendWithPadOrTruncate(CharSequence value, StringBuilder to) {
    int length = value.length();
    if (length >= PRINT_COLUMN_WIDTH) {
      to.append(value, 0, PRINT_COLUMN_WIDTH);
    } else {
      for (int i = length; i < PRINT_COLUMN_WIDTH; i++) {
        to.append(' ');
      }
      to.append(value);
    }
  }

}
