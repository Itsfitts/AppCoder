# gemini_code_modifier.py
import requests
import json
import argparse
import sys

def modify_code_with_gemini(api_key, file_content, instructions):
    """
    Send file content and instructions to Gemini API and get modified code.
    """
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={api_key}"
    
    prompt = f"""
I have the following code: {file_content} 


I want you to {instructions}

Please provide the modified code only, without any explanations.
"""
    
    payload = {
        "contents": [
            {
                "parts": [
                    {
                        "text": prompt
                    }
                ]
            }
        ]
    }
    
    headers = {
        "Content-Type": "application/json"
    }
    
    try:
        response = requests.post(url, json=payload, headers=headers)
        response.raise_for_status()
        
        response_data = response.json()
        
        if "candidates" in response_data and response_data["candidates"]:
            text_response = response_data["candidates"][0]["content"]["parts"][0]["text"]
            return text_response
        elif "promptFeedback" in response_data and "blockReason" in response_data["promptFeedback"]:
            return f"Response blocked: {response_data['promptFeedback']['blockReason']}"
        else:
            return "Empty or unexpected response from API"
    
    except requests.exceptions.RequestException as e:
        return f"API request error: {str(e)}"
    except (KeyError, IndexError) as e:
        return f"Error parsing response: {str(e)}\nRaw response: {response.text}"

def main():
    # Hard-coded file path and instructions
    file_path = r"C:\Users\chris\OneDrive\Dokumente\GitHub\AppCoder\core\test.py"
    instructions = "implement all the TODO items in the comments"
    api_key = "YourAPIKeyHere"  # Replace with your actual API key
    
    # For command-line use (if needed)
    parser = argparse.ArgumentParser(description="Modify code using Google Gemini API")
    parser.add_argument("--file", default=file_path, help="Path to the file containing code to modify")
    parser.add_argument("--instructions", default=instructions, help="Instructions for how to modify the code")
    parser.add_argument("--api-key", default=api_key, help="Google Gemini API key")
    
    args = parser.parse_args()
    
    # Use the command-line args if provided, otherwise use the hard-coded values
    file_to_use = args.file
    instructions_to_use = args.instructions
    api_key_to_use = args.api_key
    
    # Read the file content
    try:
        with open(file_to_use, 'r') as f:
            file_content = f.read()
    except IOError as e:
        print(f"Error reading file: {str(e)}")
        sys.exit(1)
    
    print(f"Sending request to Gemini API for file: {file_to_use}")
    print(f"Instructions: {instructions_to_use}")
    
    response = modify_code_with_gemini(api_key_to_use, file_content, instructions_to_use)
    
    print("\n===== GEMINI API RESPONSE =====\n")
    print(response)
    print("\n===============================\n")

if __name__ == "__main__":
    main()