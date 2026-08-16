import axios from 'axios'

/**
 * D7 unified API client. The backend returns structured {status, message} errors - the client
 * never throws raw network errors into the UI; every call resolves to either data or null.
 */
export interface ApiError {
  status: string
  message: string
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

export async function getJson<T>(path: string): Promise<T | null> {
  try {
    const response = await http.get<T>(path)
    return response.data
  } catch (error) {
    return null
  }
}
