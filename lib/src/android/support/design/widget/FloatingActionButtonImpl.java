/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.design.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.R;
import android.support.design.animation.AnimationUtils;
import android.support.design.animation.AnimatorSetCompat;
import android.support.design.animation.ImageMatrixProperty;
import android.support.design.animation.MatrixEvaluator;
import android.support.design.animation.MotionSpec;
import android.support.design.ripple.RippleUtils;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.view.ViewCompat;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.ArrayList;
import java.util.List;

class FloatingActionButtonImpl {
  static final TimeInterpolator ELEVATION_ANIM_INTERPOLATOR =
      AnimationUtils.FAST_OUT_LINEAR_IN_INTERPOLATOR;
  static final long ELEVATION_ANIM_DURATION = 100;
  static final long ELEVATION_ANIM_DELAY = 100;

  static final int ANIM_STATE_NONE = 0;
  static final int ANIM_STATE_HIDING = 1;
  static final int ANIM_STATE_SHOWING = 2;

  private static final float HIDE_OPACITY = 0f;
  private static final float HIDE_SCALE = 0f;
  private static final float HIDE_ICON_SCALE = 0f;
  private static final float SHOW_OPACITY = 1f;
  private static final float SHOW_SCALE = 1f;
  private static final float SHOW_ICON_SCALE = 1f;

  int mAnimState = ANIM_STATE_NONE;
  @Nullable Animator currentAnimator;
  @Nullable MotionSpec showMotionSpec;
  @Nullable MotionSpec hideMotionSpec;

  @Nullable private MotionSpec defaultShowMotionSpec;
  @Nullable private MotionSpec defaultHideMotionSpec;

  private final StateListAnimator mStateListAnimator;

  ShadowDrawableWrapper mShadowDrawable;

  private float mRotation;

  Drawable mShapeDrawable;
  Drawable mRippleDrawable;
  CircularBorderDrawable mBorderDrawable;
  Drawable mContentBackground;

  float mElevation;
  float mHoveredFocusedTranslationZ;
  float mPressedTranslationZ;

  int maxImageSize;
  float imageMatrixScale = 1f;

  interface InternalVisibilityChangedListener {
    void onShown();

    void onHidden();
  }

  static final int[] PRESSED_ENABLED_STATE_SET = {
    android.R.attr.state_pressed, android.R.attr.state_enabled
  };
  static final int[] HOVERED_FOCUSED_ENABLED_STATE_SET = {
    android.R.attr.state_hovered, android.R.attr.state_focused, android.R.attr.state_enabled
  };
  static final int[] FOCUSED_ENABLED_STATE_SET = {
    android.R.attr.state_focused, android.R.attr.state_enabled
  };
  static final int[] HOVERED_ENABLED_STATE_SET = {
    android.R.attr.state_hovered, android.R.attr.state_enabled
  };
  static final int[] ENABLED_STATE_SET = {android.R.attr.state_enabled};
  static final int[] EMPTY_STATE_SET = new int[0];

  final VisibilityAwareImageButton mView;
  final ShadowViewDelegate mShadowViewDelegate;

  private final Rect mTmpRect = new Rect();
  private final RectF mTmpRectF1 = new RectF();
  private final RectF mTmpRectF2 = new RectF();
  private final Matrix tmpMatrix = new Matrix();

  private ViewTreeObserver.OnPreDrawListener mPreDrawListener;

  FloatingActionButtonImpl(VisibilityAwareImageButton view, ShadowViewDelegate shadowViewDelegate) {
    mView = view;
    mShadowViewDelegate = shadowViewDelegate;

    mStateListAnimator = new StateListAnimator();

    // Elevate with translationZ when pressed, focused, or hovered
    mStateListAnimator.addState(
        PRESSED_ENABLED_STATE_SET,
        createElevationAnimator(new ElevateToPressedTranslationZAnimation()));
    mStateListAnimator.addState(
        HOVERED_FOCUSED_ENABLED_STATE_SET,
        createElevationAnimator(new ElevateToHoveredFocusedTranslationZAnimation()));
    mStateListAnimator.addState(
        FOCUSED_ENABLED_STATE_SET,
        createElevationAnimator(new ElevateToHoveredFocusedTranslationZAnimation()));
    mStateListAnimator.addState(
        HOVERED_ENABLED_STATE_SET,
        createElevationAnimator(new ElevateToHoveredFocusedTranslationZAnimation()));
    // Reset back to elevation by default
    mStateListAnimator.addState(
        ENABLED_STATE_SET, createElevationAnimator(new ResetElevationAnimation()));
    // Set to 0 when disabled
    mStateListAnimator.addState(
        EMPTY_STATE_SET, createElevationAnimator(new DisabledElevationAnimation()));

    mRotation = mView.getRotation();
  }

  void setBackgroundDrawable(
      ColorStateList backgroundTint,
      PorterDuff.Mode backgroundTintMode,
      int rippleColor,
      ColorStateList rippleAlpha,
      int borderWidth) {
    // Now we need to tint the original background with the tint, using
    // an InsetDrawable if we have a border width
    mShapeDrawable = DrawableCompat.wrap(createShapeDrawable());
    DrawableCompat.setTintList(mShapeDrawable, backgroundTint);
    if (backgroundTintMode != null) {
      DrawableCompat.setTintMode(mShapeDrawable, backgroundTintMode);
    }

    // Now we created a mask Drawable which will be used for touch feedback.
    GradientDrawable touchFeedbackShape = createShapeDrawable();

    // We'll now wrap that touch feedback mask drawable with a ColorStateList. We do not need
    // to inset for any border here as LayerDrawable will nest the padding for us
    mRippleDrawable = DrawableCompat.wrap(touchFeedbackShape);
    DrawableCompat.setTintList(
        mRippleDrawable,
        RippleUtils.compositeRippleColorStateList(
            ColorStateList.valueOf(rippleColor), rippleAlpha));

    final Drawable[] layers;
    if (borderWidth > 0) {
      mBorderDrawable = createBorderDrawable(borderWidth, backgroundTint);
      layers = new Drawable[] {mBorderDrawable, mShapeDrawable, mRippleDrawable};
    } else {
      mBorderDrawable = null;
      layers = new Drawable[] {mShapeDrawable, mRippleDrawable};
    }

    mContentBackground = new LayerDrawable(layers);

    mShadowDrawable =
        new ShadowDrawableWrapper(
            mView.getContext(),
            mContentBackground,
            mShadowViewDelegate.getRadius(),
            mElevation,
            mElevation + mPressedTranslationZ);
    mShadowDrawable.setAddPaddingForCorners(false);
    mShadowViewDelegate.setBackgroundDrawable(mShadowDrawable);
  }

  void setBackgroundTintList(ColorStateList tint) {
    if (mShapeDrawable != null) {
      DrawableCompat.setTintList(mShapeDrawable, tint);
    }
    if (mBorderDrawable != null) {
      mBorderDrawable.setBorderTint(tint);
    }
  }

  void setBackgroundTintMode(PorterDuff.Mode tintMode) {
    if (mShapeDrawable != null) {
      DrawableCompat.setTintMode(mShapeDrawable, tintMode);
    }
  }

  void setRippleColor(@ColorInt int rippleColor, ColorStateList rippleAlpha) {
    if (mRippleDrawable != null) {
      DrawableCompat.setTintList(
          mRippleDrawable,
          RippleUtils.compositeRippleColorStateList(
              ColorStateList.valueOf(rippleColor), rippleAlpha));
    }
  }

  final void setElevation(float elevation) {
    if (mElevation != elevation) {
      mElevation = elevation;
      onElevationsChanged(mElevation, mHoveredFocusedTranslationZ, mPressedTranslationZ);
    }
  }

  float getElevation() {
    return mElevation;
  }

  float getHoveredFocusedTranslationZ() {
    return mHoveredFocusedTranslationZ;
  }

  float getPressedTranslationZ() {
    return mPressedTranslationZ;
  }

  final void setHoveredFocusedTranslationZ(float translationZ) {
    if (mHoveredFocusedTranslationZ != translationZ) {
      mHoveredFocusedTranslationZ = translationZ;
      onElevationsChanged(mElevation, mHoveredFocusedTranslationZ, mPressedTranslationZ);
    }
  }

  final void setPressedTranslationZ(float translationZ) {
    if (mPressedTranslationZ != translationZ) {
      mPressedTranslationZ = translationZ;
      onElevationsChanged(mElevation, mHoveredFocusedTranslationZ, mPressedTranslationZ);
    }
  }

  final void setMaxImageSize(int maxImageSize) {
    if (this.maxImageSize != maxImageSize) {
      this.maxImageSize = maxImageSize;
      updateImageMatrixScale();
    }
  }

  /**
   * Call this whenever the image drawable changes or the view size changes.
   */
  final void updateImageMatrixScale() {
    // Recompute the image matrix needed to maintain the same scale.
    setImageMatrixScale(imageMatrixScale);
  }

  final void setImageMatrixScale(float scale) {
    this.imageMatrixScale = scale;

    Matrix matrix = tmpMatrix;
    calculateImageMatrixFromScale(scale, matrix);
    mView.setImageMatrix(matrix);
  }

  private void calculateImageMatrixFromScale(float scale, Matrix matrix) {
    matrix.reset();

    Drawable drawable = mView.getDrawable();
    if (drawable != null && maxImageSize != 0) {
      // First make sure our image respects mMaxImageSize.
      RectF drawableBounds = mTmpRectF1;
      RectF imageBounds = mTmpRectF2;
      drawableBounds.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
      imageBounds.set(0, 0, maxImageSize, maxImageSize);
      matrix.setRectToRect(drawableBounds, imageBounds, ScaleToFit.CENTER);

      // Then scale it as requested.
      matrix.postScale(scale, scale, maxImageSize / 2f, maxImageSize / 2f);
    }
  }

  @Nullable
  final MotionSpec getShowMotionSpec() {
    return showMotionSpec;
  }

  final void setShowMotionSpec(@Nullable MotionSpec spec) {
    showMotionSpec = spec;
  }

  @Nullable
  final MotionSpec getHideMotionSpec() {
    return hideMotionSpec;
  }

  final void setHideMotionSpec(@Nullable MotionSpec spec) {
    hideMotionSpec = spec;
  }

  void onElevationsChanged(
      float elevation, float hoveredFocusedTranslationZ, float pressedTranslationZ) {
    if (mShadowDrawable != null) {
      mShadowDrawable.setShadowSize(elevation, elevation + mPressedTranslationZ);
      updatePadding();
    }
  }

  void onDrawableStateChanged(int[] state) {
    mStateListAnimator.setState(state);
  }

  void jumpDrawableToCurrentState() {
    mStateListAnimator.jumpToCurrentState();
  }

  void hide(@Nullable final InternalVisibilityChangedListener listener, final boolean fromUser) {
    if (isOrWillBeHidden()) {
      // We either are or will soon be hidden, skip the call
      return;
    }

    if (currentAnimator != null) {
      currentAnimator.cancel();
    }

    if (shouldAnimateVisibilityChange()) {
      AnimatorSet set =
          createAnimator(
              hideMotionSpec != null ? hideMotionSpec : getDefaultHideMotionSpec(),
              HIDE_OPACITY,
              HIDE_SCALE,
              HIDE_ICON_SCALE);
      set.addListener(
          new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
              mView.internalSetVisibility(View.VISIBLE, fromUser);

              mAnimState = ANIM_STATE_HIDING;
              currentAnimator = animation;
              mCancelled = false;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
              mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
              mAnimState = ANIM_STATE_NONE;
              currentAnimator = null;

              if (!mCancelled) {
                mView.internalSetVisibility(fromUser ? View.GONE : View.INVISIBLE, fromUser);
                if (listener != null) {
                  listener.onHidden();
                }
              }
            }
          });
      set.start();
    } else {
      // If the view isn't laid out, or we're in the editor, don't run the animation
      mView.internalSetVisibility(fromUser ? View.GONE : View.INVISIBLE, fromUser);
      if (listener != null) {
        listener.onHidden();
      }
    }
  }

  void show(@Nullable final InternalVisibilityChangedListener listener, final boolean fromUser) {
    if (isOrWillBeShown()) {
      // We either are or will soon be visible, skip the call
      return;
    }

    if (currentAnimator != null) {
      currentAnimator.cancel();
    }

    if (shouldAnimateVisibilityChange()) {
      if (mView.getVisibility() != View.VISIBLE) {
        // If the view isn't visible currently, we'll animate it from a single pixel
        mView.setAlpha(0f);
        mView.setScaleY(0f);
        mView.setScaleX(0f);
        setImageMatrixScale(0f);
      }

      AnimatorSet set =
          createAnimator(
              showMotionSpec != null ? showMotionSpec : getDefaultShowMotionSpec(),
              SHOW_OPACITY,
              SHOW_SCALE,
              SHOW_ICON_SCALE);
      set.addListener(
          new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
              mView.internalSetVisibility(View.VISIBLE, fromUser);

              mAnimState = ANIM_STATE_SHOWING;
              currentAnimator = animation;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
              mAnimState = ANIM_STATE_NONE;
              currentAnimator = null;

              if (listener != null) {
                listener.onShown();
              }
            }
          });
      set.start();
    } else {
      mView.internalSetVisibility(View.VISIBLE, fromUser);
      mView.setAlpha(1f);
      mView.setScaleY(1f);
      mView.setScaleX(1f);
      setImageMatrixScale(1f);
      if (listener != null) {
        listener.onShown();
      }
    }
  }

  private MotionSpec getDefaultShowMotionSpec() {
    if (defaultShowMotionSpec == null) {
      defaultShowMotionSpec =
          MotionSpec.createFromResource(mView.getContext(), R.animator.design_fab_show_motion_spec);
    }
    return defaultShowMotionSpec;
  }

  private MotionSpec getDefaultHideMotionSpec() {
    if (defaultHideMotionSpec == null) {
      defaultHideMotionSpec =
          MotionSpec.createFromResource(mView.getContext(), R.animator.design_fab_hide_motion_spec);
    }
    return defaultHideMotionSpec;
  }

  @NonNull
  private AnimatorSet createAnimator(
      @NonNull MotionSpec spec, float opacity, float scale, float iconScale) {
    List<Animator> animators = new ArrayList<>();
    Animator animator;

    animator = ObjectAnimator.ofFloat(mView, View.ALPHA, opacity);
    spec.getTiming("opacity").apply(animator);
    animators.add(animator);

    animator = ObjectAnimator.ofFloat(mView, View.SCALE_X, scale);
    spec.getTiming("scale").apply(animator);
    animators.add(animator);

    animator = ObjectAnimator.ofFloat(mView, View.SCALE_Y, scale);
    spec.getTiming("scale").apply(animator);
    animators.add(animator);

    calculateImageMatrixFromScale(iconScale, tmpMatrix);
    animator =
        ObjectAnimator.ofObject(
            mView, new ImageMatrixProperty(), new MatrixEvaluator(), new Matrix(tmpMatrix));
    spec.getTiming("iconScale").apply(animator);
    animators.add(animator);

    AnimatorSet set = new AnimatorSet();
    AnimatorSetCompat.playTogether(set, animators);
    return set;
  }

  final Drawable getContentBackground() {
    return mContentBackground;
  }

  void onCompatShadowChanged() {
    // Ignore pre-v21
  }

  final void updatePadding() {
    Rect rect = mTmpRect;
    getPadding(rect);
    onPaddingUpdated(rect);
    mShadowViewDelegate.setShadowPadding(rect.left, rect.top, rect.right, rect.bottom);
  }

  void getPadding(Rect rect) {
    mShadowDrawable.getPadding(rect);
  }

  void onPaddingUpdated(Rect padding) {}

  void onAttachedToWindow() {
    if (requirePreDrawListener()) {
      ensurePreDrawListener();
      mView.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
    }
  }

  void onDetachedFromWindow() {
    if (mPreDrawListener != null) {
      mView.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
      mPreDrawListener = null;
    }
  }

  boolean requirePreDrawListener() {
    return true;
  }

  CircularBorderDrawable createBorderDrawable(int borderWidth, ColorStateList backgroundTint) {
    final Context context = mView.getContext();
    CircularBorderDrawable borderDrawable = newCircularDrawable();
    borderDrawable.setGradientColors(
        ContextCompat.getColor(context, R.color.design_fab_stroke_top_outer_color),
        ContextCompat.getColor(context, R.color.design_fab_stroke_top_inner_color),
        ContextCompat.getColor(context, R.color.design_fab_stroke_end_inner_color),
        ContextCompat.getColor(context, R.color.design_fab_stroke_end_outer_color));
    borderDrawable.setBorderWidth(borderWidth);
    borderDrawable.setBorderTint(backgroundTint);
    return borderDrawable;
  }

  CircularBorderDrawable newCircularDrawable() {
    return new CircularBorderDrawable();
  }

  void onPreDraw() {
    final float rotation = mView.getRotation();
    if (mRotation != rotation) {
      mRotation = rotation;
      updateFromViewRotation();
    }
  }

  private void ensurePreDrawListener() {
    if (mPreDrawListener == null) {
      mPreDrawListener =
          new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
              FloatingActionButtonImpl.this.onPreDraw();
              return true;
            }
          };
    }
  }

  GradientDrawable createShapeDrawable() {
    GradientDrawable d = newGradientDrawableForShape();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(Color.WHITE);
    return d;
  }

  GradientDrawable newGradientDrawableForShape() {
    return new GradientDrawable();
  }

  boolean isOrWillBeShown() {
    if (mView.getVisibility() != View.VISIBLE) {
      // If we not currently visible, return true if we're animating to be shown
      return mAnimState == ANIM_STATE_SHOWING;
    } else {
      // Otherwise if we're visible, return true if we're not animating to be hidden
      return mAnimState != ANIM_STATE_HIDING;
    }
  }

  boolean isOrWillBeHidden() {
    if (mView.getVisibility() == View.VISIBLE) {
      // If we currently visible, return true if we're animating to be hidden
      return mAnimState == ANIM_STATE_HIDING;
    } else {
      // Otherwise if we're not visible, return true if we're not animating to be shown
      return mAnimState != ANIM_STATE_SHOWING;
    }
  }

  private ValueAnimator createElevationAnimator(@NonNull ShadowAnimatorImpl impl) {
    final ValueAnimator animator = new ValueAnimator();
    animator.setInterpolator(ELEVATION_ANIM_INTERPOLATOR);
    animator.setDuration(ELEVATION_ANIM_DURATION);
    animator.addListener(impl);
    animator.addUpdateListener(impl);
    animator.setFloatValues(0, 1);
    return animator;
  }

  private abstract class ShadowAnimatorImpl extends AnimatorListenerAdapter
      implements ValueAnimator.AnimatorUpdateListener {
    private boolean mValidValues;
    private float mShadowSizeStart;
    private float mShadowSizeEnd;

    @Override
    public void onAnimationUpdate(ValueAnimator animator) {
      if (!mValidValues) {
        mShadowSizeStart = mShadowDrawable.getShadowSize();
        mShadowSizeEnd = getTargetShadowSize();
        mValidValues = true;
      }

      mShadowDrawable.setShadowSize(
          mShadowSizeStart
              + ((mShadowSizeEnd - mShadowSizeStart) * animator.getAnimatedFraction()));
    }

    @Override
    public void onAnimationEnd(Animator animator) {
      mShadowDrawable.setShadowSize(mShadowSizeEnd);
      mValidValues = false;
    }

    /** @return the shadow size we want to animate to. */
    protected abstract float getTargetShadowSize();
  }

  private class ResetElevationAnimation extends ShadowAnimatorImpl {
    ResetElevationAnimation() {}

    @Override
    protected float getTargetShadowSize() {
      return mElevation;
    }
  }

  private class ElevateToHoveredFocusedTranslationZAnimation extends ShadowAnimatorImpl {
    ElevateToHoveredFocusedTranslationZAnimation() {}

    @Override
    protected float getTargetShadowSize() {
      return mElevation + mHoveredFocusedTranslationZ;
    }
  }

  private class ElevateToPressedTranslationZAnimation extends ShadowAnimatorImpl {
    ElevateToPressedTranslationZAnimation() {}

    @Override
    protected float getTargetShadowSize() {
      return mElevation + mPressedTranslationZ;
    }
  }

  private class DisabledElevationAnimation extends ShadowAnimatorImpl {
    DisabledElevationAnimation() {}

    @Override
    protected float getTargetShadowSize() {
      return 0f;
    }
  }

  private boolean shouldAnimateVisibilityChange() {
    return ViewCompat.isLaidOut(mView) && !mView.isInEditMode();
  }

  private void updateFromViewRotation() {
    if (Build.VERSION.SDK_INT == 19) {
      // KitKat seems to have an issue with views which are rotated with angles which are
      // not divisible by 90. Worked around by moving to software rendering in these cases.
      if ((mRotation % 90) != 0) {
        if (mView.getLayerType() != View.LAYER_TYPE_SOFTWARE) {
          mView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
      } else {
        if (mView.getLayerType() != View.LAYER_TYPE_NONE) {
          mView.setLayerType(View.LAYER_TYPE_NONE, null);
        }
      }
    }

    // Offset any View rotation
    if (mShadowDrawable != null) {
      mShadowDrawable.setRotation(-mRotation);
    }
    if (mBorderDrawable != null) {
      mBorderDrawable.setRotation(-mRotation);
    }
  }
}
