import os, sys
import math
import numpy as np
import pandas as pd

# Bandpass signal functions useful in filtering
def butter_bandpass(lowcut, highcut, fs=60, order=5):
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    return b, a

def butter_bandpass_filter(data, lowcut, highcut, fs=60, order=5):
    b, a = butter_bandpass(lowcut, highcut, fs, order=order)
    y = lfilter(b, a, data)
    return y

# Gets the heartrate signal from json?
def get_heartrate(heartrate_data, window_length=10, lowcut=1, highcut=25, bandpass_order=128):
	# TODO: heartrate datafram? not sure if this is needed
	null_df = pd.DataFrame(['N/A', 'N/A', 'N/A', 'N/A', 'N/A'], columns=['red', 'green', 'blue', 'error', 'sampling_rate'])
	
	sampling_rate = 60

	if sampling_rate < 55:
		if sampling_rate > 22:
			bandpass_order = 64
		else:
			bandpass_order = 32

	window_length = int(sampling_rate * window_length)
	mforder = 2 * int(60 * sampling_rate/220) + 1

	# If it's a poorly created dataframe, return the null one
	if heartrate_data is None:
		return null_df

	red = heartrate_data['r']
	green = heartrate_data['g']
	blue = heartrate_data['b']

	if red is None or green is None or blue is None:
		return null_df

	red = get_filtered_signal(red, sampling_rate, mforder, bandpass_order, lowcut, highcut)
	green = get_filtered_signal(green, sampling_rate, mforder, bandpass_order, lowcut, highcut)
	blue = get_filtered_signal(blue, sampling_rate, mforder, bandpass_order, lowcut, highcut)

	# Get HR
	red_hr, r_conf = get_hr_from_time_series(red, sampling_rate, 40, 200)
	blue_hr, b_conf = get_hr_from_time_series(blue, sampling_rate, 40, 200)
	green_hr, g_conf = get_hr_from_time_series(green, sampling_rate, 40, 200)

	if red_hr is None or green_hr is None or blue_hr is None:
		return null_df

	if sampling_rate < 55:
		print("Low sampling rate")

	# Create the dataframe to return (cols: r, g, b, sampling_rate, error)
	columns = ['R', 'G', 'B', 'sampling_rate']
	records = []

	for i in range(len(red)):
		row = (red_hr[i], green_hr[i], blue_hr[i], sampling_rate)
		records.append(row)

	heartrate_df = pd.DataFrame.from_records(records, columns=columns) 


	return 0

def replace_with_zero(arr):
	refreshed = []
	for i in range(len(arr)):
		if arr[i] is None:
			refreshed.append(0)
		else:
			refreshed.append(arr[i])

	return refreshed
	
#' Bandpass and sorted mean filter the given signal
#'
#' @param x A time series numeric data
#' @param mean_filter_order Length of the sorted mean filter window
#' @param frequency_range Frequency range in Hz for the bandpass filter parameters
#' @param bandpass_order Order (length) of the bandpass filter to be used for filtering
#' @param sampling_rate The sampling rate (fs) of the time series data
#' @return The filtered time series data
def get_filtered_signal(x, sampling_rate, mean_filter_order=33, bandpass_order=128, lowcut=2, highcut=25):
	# If we have NaN data, make it 0
	for i in range(len(x)):
		if x[i] is None:
			x[i] = 0

	# Butter Bandpass Filter
	x = butter_bandpass_filter(x, lowcut, highcut, sampling_rate, bandpass_order)

	# Do mean filter on given signal
	y = [0] * len(x)
	for i in range(len(x)):
		if i < mean_filter_order:
			continue
		temp_sequence = x[int(i - order/2): int(i + order/2 + 1)]
		mean = np.mean(temp_sequence)

		temp_sequence = [x - mean for x in temp_sequence]
		
		max_val = max(temp_sequence)
		min_val = min(temp_sequence)
		sum_val = np.sum(temp_sequence)
		constant = 0.0000001


		y[i] = (((x[i] - max_val - min_val) - (sum_val - max_val) / (order - 1)) /
               (max_val - min_val + constant))

	return y


def get_hr_from_time_series(x, sampling_rate, min_hr=40, max_hr=200):
	for i in range(len(x)):
		if x[i] is None:
			x[i] = 0
	
	x = np.correlate(x, x, 'full')
	x = x[int(len(x)/2):]

	y = [0] * len(x)

	for i in range(int(60*sampling_rate/max_hr), int(60 * sampling_rate/min_hr)):
		y[i] = x[i]

	confidence = max(y) / float(max(x))

	hr = 60 * sampling_rate / (np.argmin(y) - 1)

	if hr is None or confidence is None:
		return (0, 0)

	return (hr, confidence)

