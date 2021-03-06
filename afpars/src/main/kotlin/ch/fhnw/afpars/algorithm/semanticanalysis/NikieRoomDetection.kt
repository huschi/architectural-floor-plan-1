package ch.fhnw.afpars.algorithm.semanticanalysis

import ch.fhnw.afpars.algorithm.AlgorithmParameter
import ch.fhnw.afpars.algorithm.IAlgorithm
import ch.fhnw.afpars.algorithm.informationsegmentation.MorphologicalTransform
import ch.fhnw.afpars.algorithm.structuralanalysis.CascadeClassifierDetector
import ch.fhnw.afpars.model.AFImage
import ch.fhnw.afpars.util.*
import ch.fhnw.afpars.util.opencv.combinePoints
import ch.fhnw.afpars.util.opencv.sparsePoints
import org.opencv.core.*
import org.opencv.imgproc.Imgproc


/**
 * Based on http://mathematica.stackexchange.com/a/19550/43125 by nikie
 */
class NikieRoomDetection : IAlgorithm {
    companion object {
        //Colors
        val BLACK = 0.0
        val GRAY = 128.0
        val LIGHTGRAY = 200.0
        val WHITE = 255.0

        //Threshold
        val THRESHOLD = GRAY
        val MAXVAL = WHITE

        //Angles
        val DOORCLOSINGANGLE = 2 * Math.PI / 180
        val ERRORRECT = 2 * Math.PI / 180

        //Normalize
        val ALPHA = BLACK
        val BETA = WHITE

        //Harris corner
        val BLOCKSIZE = 3
        val KSIZE = 5
        val K = 0.04

        val CORNERMIN = 170

        //Sparse points
        val RADIUS = 8

        //Door detection
        val ADDDETECTRATIO = 0.25
        val DOORSIZEFACTOR = 1.25
    }

    override val name: String
        get() = "Nikie Room Detection"

    @AlgorithmParameter(name = "Difference Scalar")
    var differenceScalar = 70.0

    @AlgorithmParameter(name = "Geodesic Dilate")
    var geodesicDilateSize = 60

    @AlgorithmParameter(name = "Distance", minValue = 1.0, maxValue = 10.0)
    var distance1 = 2


    /*
    Input ist ein morphologisch transformiertes Bild
    Eingabetyp ist 32SC1
     */
    override fun run(image: AFImage, history: MutableList<AFImage>): AFImage {
        val watch = Stopwatch()
        watch.start()

        //Originalbild
        val original = AFImage(image.attributes.get(MorphologicalTransform.MORPH)!!)

        //Distanztransformatin
        val distTransform = original.image.zeros()
        //GeodesicTransform
        val geodesicTransform = original.image.zeros()
        //Markers
        val markers = original.image.zeros()
        //Foreground
        val foreground = original.image.zeros()


        /*
        Distanztranformation
        Geht vom Originalbild aus, rückgabe ist das Bild "distTransform"
        Konvertiert das Bild zu 8UC1
         */
        var localoriginal = distanceTransform(distTransform, original, watch)

        //Background
        val background = localoriginal.copy()
        //Summed grounds
        val summedUp = localoriginal.zeros()


        /*
        GeodesicDilation
         */
        geodesicDilation(distTransform, geodesicTransform, markers, original, watch)

        /*
       Cornerdetection
        */
        val triple = cornerDetection(localoriginal, watch)
        var cornerdet = triple.first
        val cornerdetnormscaled = triple.second
        val sparsePoints = triple.third

        /*Invert markers*/
        Imgproc.threshold(markers, foreground, THRESHOLD, MAXVAL, Imgproc.THRESH_BINARY_INV)

        /*
        findContours
         */
        println("${watch.elapsed().toTimeStamp()}\nprepare watershed")
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = original.image.zeros()
        Imgproc.findContours(foreground.copy(), contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)

        // Create the marker image for the watershed algorithm
        val contmarkers = image.image.zeros(CvType.CV_32SC1)
        contmarkers.setTo(Scalar(WHITE))

        //Draw foreground markers
        drawForeground(contmarkers, contours)

        /*
        Background
         */
        drawBackground(background)

        var watershedoriginal = localoriginal.copy()
        Imgproc.cvtColor(localoriginal, watershedoriginal, Imgproc.COLOR_GRAY2BGR)
        watershedoriginal = watershedoriginal.to8UC3()

        /*Close doors */
        doorClosing(image, sparsePoints, watch, watershedoriginal)

        /*
        Kombination Background/Foreground
         */
        createFGBG(background, contmarkers, summedUp)

        println("${watch.elapsed().toTimeStamp()}\nwatersheding")
        val watershed = summedUp.to32S()

        Imgproc.watershed(watershedoriginal, watershed)

        history.add(AFImage(cornerdet, "Cornerdet"))
        history.add(AFImage(cornerdetnormscaled, "CornerdetScaled"))
        history.add(AFImage(distTransform, "Distanztransformation"))
        history.add(AFImage(geodesicTransform, "Geodesictransformation"))
        history.add(AFImage(markers, "Markers"))
        history.add(AFImage(foreground, "Foreground"))
        history.add(AFImage(contmarkers, "findContour"))
        history.add(AFImage(background, "Background"))
        history.add(AFImage(summedUp, "Summed Up"))
        history.add(AFImage(watershedoriginal, "Orig with door closing"))
        history.add(AFImage(watershed, "Watershed"))

        println("${watch.elapsed().toTimeStamp()}\n finished! ${watch.stop().toTimeStamp()}")
        return AFImage(watershed)
    }

    private fun createFGBG(background: Mat, contmarkers: Mat, summedUp: Mat) {
        for (i in 0..summedUp.height() - 1) {
            for (j in 0..summedUp.width() - 1) {
                summedUp.put(i, j, contmarkers.get(i, j)[0] + background.get(i, j)[0])
            }
        }
    }

    private fun distanceTransform(distTransform: Mat, original: AFImage, watch: Stopwatch): Mat {
        println("${watch.elapsed().toTimeStamp()}\nDistanztransform")
        var localoriginal = original.image.zeros()
        Imgproc.cvtColor(original.image, localoriginal, Imgproc.COLOR_BGR2GRAY)
        Imgproc.distanceTransform(localoriginal, distTransform, Imgproc.CV_DIST_L2, Imgproc.CV_DIST_MASK_PRECISE)
        return localoriginal
    }

    private fun doorClosing(image: AFImage, sparsePoints: MutableList<Point>, watch: Stopwatch, watershedoriginal: Mat) {
        println("${watch.elapsed().toTimeStamp()}\nClose doors")
        val foundDoors: MatOfRect = image.attributes.get(AFImage.DOOR_ATTRIBUTE_NAME) as MatOfRect
        val foundDoorsArray = foundDoors.toArray()

        for (i in 0..foundDoors.rows() - 1) {
            val door = foundDoorsArray[i]
            val doorPoints = mutableListOf<Point>()
            sparsePoints.forEach { point: Point ->
                if (point.x < door.x + door.width + (door.width* ADDDETECTRATIO) && point.x > door.x - (door.width* ADDDETECTRATIO) && point.y < door.y + door.height + (door.height* ADDDETECTRATIO) && point.y > door.y - (door.height* ADDDETECTRATIO)) {
                    doorPoints.add(point)
                }
            }

            val angles = Array(doorPoints.size) { arrayOfNulls<Double>(doorPoints.size) }
            for (j in 0..doorPoints.size - 1) {
                for (k in (j + 1)..doorPoints.size - 1) {
                    angles[j][k] = angleToXAxis(doorPoints[j], doorPoints[k])
                    angles[k][j] = angleToXAxis(doorPoints[j], doorPoints[k])
                }
            }


            //Neu
            if (!angles.isEmpty()) {
                val size = angles[0].size - 1
                for (j in 0..size) {
                    for (k in (j + 1)..size) {
                        outerloop@ for (innerJ in j + 1..size) {
                            if (innerJ == k) continue@outerloop
                            innerloop@ for (innerK in (innerJ + 1)..size) {
                                if (innerK == k) continue@innerloop
                                if (innerJ != j && innerK != k && innerJ != k && innerK != j) {
                                    if ((angles[j][k] as Double).isApproximate(angles[innerJ][innerK] as Double, DOORCLOSINGANGLE)) {
                                        if ((angles[j][innerJ] as Double).isApproximate(angles[k][innerK] as Double, DOORCLOSINGANGLE)&& (angles[j][innerJ] as Double).isRectangular(angles[j][k] as Double, ERRORRECT)) {
                                            if(Math.abs(doorPoints[j].x - doorPoints[k].x)< DOORSIZEFACTOR*door.width && Math.abs(doorPoints[j].y - doorPoints[innerJ].y)< DOORSIZEFACTOR*door.height )
                                                Imgproc.rectangle(watershedoriginal, doorPoints[j], doorPoints[innerK], Scalar(BLACK), -1)
                                        } else if ((angles[j][innerK] as Double).isApproximate(angles[k][innerJ] as Double, DOORCLOSINGANGLE)&& (angles[j][innerK] as Double).isRectangular(angles[j][k] as Double, ERRORRECT)) {
                                            if(Math.abs(doorPoints[j].x - doorPoints[k].x)< DOORSIZEFACTOR*door.width && Math.abs(doorPoints[j].y - doorPoints[innerK].y)< DOORSIZEFACTOR*door.height )
                                                Imgproc.rectangle(watershedoriginal, doorPoints[j], doorPoints[innerK], Scalar(BLACK), -1)
                                        }
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    private fun drawBackground(background: Mat) {
        background.replaceColor(Scalar(BLACK), Scalar(GRAY))
        background.replaceColor(Scalar(WHITE), Scalar(BLACK))
    }

    private fun drawForeground(contmarkers: Mat, contours: MutableList<MatOfPoint>) {
        for (cnt in contours) {
            Imgproc.drawContours(contmarkers, mutableListOf(cnt), 0, Scalar.all(LIGHTGRAY), Core.FILLED)
        }

        contmarkers.replaceColor(Scalar(WHITE), Scalar(BLACK))

    }

    private fun cornerDetection(localoriginal: Mat, watch: Stopwatch): Triple<Mat, Mat, MutableList<Point>> {
        println("${watch.elapsed().toTimeStamp()}\nCornerdetection")
        var cornerdet = localoriginal.copy()
        val cornerdetnorm = cornerdet.zeros()
        val cornerdetnormscaled = cornerdet.zeros()

        Imgproc.cornerHarris(cornerdet, cornerdet, BLOCKSIZE, KSIZE, K)
        Core.normalize(cornerdet, cornerdetnorm, ALPHA, BETA, Core.NORM_MINMAX, CvType.CV_32FC1, Mat())
        Core.convertScaleAbs(cornerdetnorm, cornerdetnormscaled)
        val threshhigh = CORNERMIN
        val points = mutableListOf<Point>()
        // Drawing a circle around corners
        for (j in 0..cornerdetnorm.rows() - 1) {
            var text = ""
            for (i in 0..cornerdetnorm.cols() - 1) {
                text += cornerdetnormscaled.get(j, i)[0].toInt().toString() + " "
                val point = cornerdetnorm.get(j, i)[0]
                if (point > threshhigh) {
                    points.add(Point(i.toDouble(), j.toDouble()))
                }
            }
        }

        println("found ${points.size} corners!")

        println("${watch.elapsed().toTimeStamp()}\nSparsing Points")
        val sparsePoints = points.sparsePoints(distance1.toDouble()).combinePoints()

        println("sparsed point cloud to ${sparsePoints.size} points!")

        for (p in sparsePoints)
            Imgproc.circle(cornerdetnormscaled, p, RADIUS, Scalar(BLACK, BLACK, WHITE))
        return Triple(cornerdet, cornerdetnormscaled, sparsePoints)
    }

    private fun geodesicDilation(distTransform: Mat, geodesicTransform: Mat, markers: Mat, original: AFImage, watch: Stopwatch) {
        println("${watch.elapsed().toTimeStamp()}\nGeodesicDilation")
        val darkerDistTransform = original.image.zeros()
        Core.subtract(distTransform, Scalar(differenceScalar), darkerDistTransform)
        darkerDistTransform.geodesicDilate(distTransform, geodesicDilateSize, geodesicTransform)
        Core.compare(distTransform, geodesicTransform, markers, Core.CMP_LE)
    }

    fun angleToXAxis(point1: Point, point2: Point): Double {
        val delta = Point(point1.x - point2.x, point1.y - point2.y)
        return -Math.atan(delta.y / delta.x)
    }
}